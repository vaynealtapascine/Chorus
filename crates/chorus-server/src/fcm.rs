//! Firebase Cloud Messaging (D-096): instant push to Android phones with Google Play services and
//! no UnifiedPush distributor. A phone registers `fcm:<registration token>` as its push endpoint
//! (with the same Web Push keys as any device), and [`crate::push::send`] hands those here.
//!
//! The payload is still the RFC 8291 ciphertext only that phone can read; FCM carries it as one
//! base64url data field (`c`), so Google sees that a phone got something, never what. Sending
//! needs an OAuth token for the operator's Firebase project: a JWT signed with the service
//! account's RSA key (`push.fcm_service_account`, a file only root and the service can read),
//! exchanged at Google's token endpoint and kept until shortly before it expires.

use std::sync::{Mutex, OnceLock};

use aws_lc_rs::rand::SystemRandom;
use aws_lc_rs::signature::{RSA_PKCS1_SHA256, RsaKeyPair};
use base64::Engine as _;
use serde::Deserialize;

/// Largest plaintext an FCM push carries: FCM caps a message's data at 4 KB, and the field holds
/// base64 of the encrypted record (+86 bytes of header, +17 of padding and tag).
pub const MAX_PAYLOAD: usize = 2700;
const SCOPE: &str = "https://www.googleapis.com/auth/firebase.messaging";

#[derive(Deserialize)]
struct ServiceAccount {
    project_id: String,
    client_email: String,
    private_key: String,
    token_uri: String,
}

pub struct Fcm {
    project: String,
    email: String,
    token_uri: String,
    key: RsaKeyPair,
    /// (access token, expires at ms)
    token: Mutex<Option<(String, i64)>>,
}

static FCM: OnceLock<Option<Fcm>> = OnceLock::new();

/// Load the service account once at startup; without one FCM endpoints are refused.
pub fn init(path: Option<&std::path::Path>) {
    let fcm = path.and_then(|p| match Fcm::load(p) {
        Ok(f) => {
            tracing::info!(project = %f.project, "FCM push on");
            Some(f)
        }
        Err(e) => {
            tracing::error!(error = %e, path = %p.display(), "FCM service account unusable; FCM push off");
            None
        }
    });
    let _ = FCM.set(fcm);
}

pub fn get() -> Option<&'static Fcm> {
    FCM.get().and_then(Option::as_ref)
}

/// A registration token as FCM hands them out (no URL, no spaces); refused otherwise.
pub fn valid_token(t: &str) -> bool {
    (32..=4096).contains(&t.len()) && t.bytes().all(|b| b.is_ascii_alphanumeric() || b"-_:.".contains(&b))
}

/// The DER inside a PEM `PRIVATE KEY` block.
fn pem_der(pem: &str) -> anyhow::Result<Vec<u8>> {
    let body: String = pem.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with("-----")).collect();
    Ok(base64::engine::general_purpose::STANDARD.decode(body)?)
}

/// What sending gave.
pub enum Outcome {
    Ok,
    /// The token no longer reaches an app (uninstalled, data cleared, re-registered).
    Gone,
    Failed,
}

impl Fcm {
    pub fn load(path: &std::path::Path) -> anyhow::Result<Fcm> {
        let sa: ServiceAccount = serde_json::from_slice(&std::fs::read(path)?)?;
        Self::from_account(sa)
    }

    fn from_account(sa: ServiceAccount) -> anyhow::Result<Fcm> {
        let key = RsaKeyPair::from_pkcs8(&pem_der(&sa.private_key)?)
            .map_err(|e| anyhow::anyhow!("service account key: {e}"))?;
        Ok(Fcm {
            project: sa.project_id,
            email: sa.client_email,
            token_uri: sa.token_uri,
            key,
            token: Mutex::new(None),
        })
    }

    /// The signed assertion Google's token endpoint trades for an access token.
    fn assertion(&self, now_s: i64) -> anyhow::Result<String> {
        let header = crate::push::b64url(br#"{"alg":"RS256","typ":"JWT"}"#);
        let claims = crate::push::b64url(
            serde_json::json!({
                "iss": self.email, "scope": SCOPE, "aud": self.token_uri, "iat": now_s, "exp": now_s + 3600,
            })
            .to_string()
            .as_bytes(),
        );
        let signed = format!("{header}.{claims}");
        let mut sig = vec![0; self.key.public_modulus_len()];
        self.key
            .sign(&RSA_PKCS1_SHA256, &SystemRandom::new(), signed.as_bytes(), &mut sig)
            .map_err(|_| anyhow::anyhow!("signing failed"))?;
        Ok(format!("{signed}.{}", crate::push::b64url(&sig)))
    }

    async fn access_token(&self, http: &reqwest::Client) -> anyhow::Result<String> {
        let now = crate::now_ms();
        if let Some((t, until)) = self.token.lock().unwrap().clone()
            && until > now + 60_000
        {
            return Ok(t);
        }
        let body = format!(
            "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion={}",
            self.assertion(now / 1000)?
        );
        let r = http
            .post(&self.token_uri)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(body)
            .send()
            .await?;
        let status = r.status();
        let v: serde_json::Value = r.json().await?;
        let token = v["access_token"].as_str().ok_or_else(|| anyhow::anyhow!("token endpoint said {status}: {v}"))?;
        let until = now + v["expires_in"].as_i64().unwrap_or(3600) * 1000;
        *self.token.lock().unwrap() = Some((token.to_string(), until));
        Ok(token.to_string())
    }

    /// Send one encrypted record (`aes128gcm` bytes) to a registration token.
    pub async fn send(&self, token: &str, record: &[u8]) -> Outcome {
        let http = reqwest::Client::new();
        let access = match self.access_token(&http).await {
            Ok(t) => t,
            Err(e) => {
                tracing::warn!(error = %e, "fcm: no access token");
                return Outcome::Failed;
            }
        };
        let message = serde_json::json!({"message": {
            "token": token,
            "data": {"c": crate::push::b64url(record)},
            // delivered now even to a dozing phone; kept a day if it's off
            "android": {"priority": "high", "ttl": "86400s"},
        }});
        let url = format!("https://fcm.googleapis.com/v1/projects/{}/messages:send", self.project);
        match http.post(url).bearer_auth(access).json(&message).send().await {
            Ok(r) if r.status().is_success() => Outcome::Ok,
            Ok(r) => {
                let status = r.status().as_u16();
                let text = r.text().await.unwrap_or_default();
                if status == 401 {
                    // a revoked or stale access token: fetch a new one next time
                    *self.token.lock().unwrap() = None;
                }
                if status == 404 || text.contains("UNREGISTERED") || text.contains("not a valid FCM registration token")
                {
                    Outcome::Gone
                } else {
                    tracing::warn!(status, body = %text.chars().take(300).collect::<String>(), "fcm: refused");
                    Outcome::Failed
                }
            }
            Err(e) => {
                tracing::warn!(error = %e, "fcm: send failed");
                Outcome::Failed
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aws_lc_rs::encoding::AsDer;
    use aws_lc_rs::rsa::{KeyPair, KeySize};
    use aws_lc_rs::signature::{KeyPair as _, RSA_PKCS1_2048_8192_SHA256, UnparsedPublicKey};

    fn account() -> (ServiceAccount, Vec<u8>) {
        let key = KeyPair::generate(KeySize::Rsa2048).unwrap();
        let der = key.as_der().unwrap();
        let pem = format!(
            "-----BEGIN PRIVATE KEY-----\n{}\n-----END PRIVATE KEY-----\n",
            base64::engine::general_purpose::STANDARD.encode(der.as_ref())
        );
        let public = key.public_key().as_ref().to_vec();
        let sa = ServiceAccount {
            project_id: "p".into(),
            client_email: "push@p.iam.gserviceaccount.com".into(),
            private_key: pem,
            token_uri: "https://oauth2.googleapis.com/token".into(),
        };
        (sa, public)
    }

    #[test]
    fn the_assertion_is_a_jwt_google_can_verify() {
        let (sa, public) = account();
        let fcm = Fcm::from_account(sa).unwrap();
        let jwt = fcm.assertion(1_790_000_000).unwrap();
        let parts: Vec<&str> = jwt.split('.').collect();
        assert_eq!(parts.len(), 3);
        let claims: serde_json::Value =
            serde_json::from_slice(&base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(parts[1]).unwrap())
                .unwrap();
        assert_eq!(claims["iss"], "push@p.iam.gserviceaccount.com");
        assert_eq!(claims["scope"], SCOPE);
        assert_eq!(claims["exp"], 1_790_003_600);
        let sig = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(parts[2]).unwrap();
        UnparsedPublicKey::new(&RSA_PKCS1_2048_8192_SHA256, &public)
            .verify(format!("{}.{}", parts[0], parts[1]).as_bytes(), &sig)
            .expect("signature verifies with the account's public key");
    }

    #[test]
    fn tokens_are_checked_and_payloads_fit() {
        assert!(valid_token(&"a".repeat(40)));
        assert!(valid_token("dK3x9:APA91bH-r_mJx.Q7".repeat(3).as_str()));
        assert!(!valid_token("https://evil.example/endpoint/0123456789abcdef"));
        assert!(!valid_token("short"));
        // the biggest plaintext, encrypted (86 header + 1 pad + 16 tag) and base64ed, stays under 4 KB
        assert!((MAX_PAYLOAD + 86 + 17).div_ceil(3) * 4 < 3900);
    }

    /// Against Google, with a real service account: `CHORUS_FCM_SA=<path> cargo test -p chorus-server
    /// --lib fcm::tests::live_token -- --ignored`. Only checks that the key buys an access token.
    #[tokio::test]
    #[ignore]
    async fn live_token() {
        let fcm = Fcm::load(std::path::Path::new(&std::env::var("CHORUS_FCM_SA").unwrap())).unwrap();
        let t = fcm.access_token(&reqwest::Client::new()).await.unwrap();
        assert!(t.len() > 20);
    }
}
