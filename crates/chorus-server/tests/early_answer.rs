//! A server that answers before it has read the request body (401 before the body, 415 on a
//! wrong content type, 413) must still take the body off the wire. Otherwise the body arrives at a
//! closed socket, the server's TCP stack answers with a reset, and Windows throws away the answer
//! the client had already received: the client sees "connection aborted" (os error 10053/10054)
//! instead of a 401, and the web app takes an expired session for being offline (R35).
//!
//! Linux keeps the answer readable, so this test sends the body late and watches for the reset
//! the late part meets.

mod common;

use std::io::{ErrorKind, Read, Write};
use std::net::TcpStream;
use std::time::Duration;

use chorus_server::config::Config;
use chorus_server::{app, db};

/// How one request went: the answer's status line, whether the late parts of the body went
/// through, and how the connection ended ("closed" or "kept alive").
type Outcome = (String, std::io::Result<()>, std::io::Result<&'static str>);

/// Sends the head of a request and a third of its body, then — after the server has had time to
/// answer — the rest in two late parts, and reads to the end.
fn early(addr: std::net::SocketAddr, head: &str, body_len: usize) -> Outcome {
    let mut s = TcpStream::connect(addr).unwrap();
    s.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    s.write_all(head.as_bytes()).unwrap();
    s.write_all(&vec![b'x'; body_len / 3]).unwrap();
    // the rest of the body, late: a server that closed without reading it answers the first of
    // these writes with a reset, which the second one reports
    let mut late = Ok(());
    for part in [body_len / 3, body_len - 2 * (body_len / 3)] {
        std::thread::sleep(Duration::from_millis(150));
        if let Err(e) = s.write_all(&vec![b'x'; part]) {
            late = Err(e);
            break;
        }
    }
    let mut got = Vec::new();
    let mut buf = [0u8; 4096];
    let end = loop {
        match s.read(&mut buf) {
            Ok(0) => break Ok("closed"),
            Ok(n) => {
                got.extend_from_slice(&buf[..n]);
                if let Some(at) = got.windows(4).position(|w| w == b"\r\n\r\n") {
                    let head = String::from_utf8_lossy(&got[..at]).to_ascii_lowercase();
                    let len = head
                        .lines()
                        .find_map(|l| l.strip_prefix("content-length:"))
                        .and_then(|v| v.trim().parse::<usize>().ok());
                    if len.is_some_and(|len| got.len() >= at + 4 + len) {
                        // the whole answer is in: now see whether the connection stays, closes or resets
                        s.set_read_timeout(Some(Duration::from_millis(700))).unwrap();
                    }
                }
            }
            Err(e) if e.kind() == ErrorKind::WouldBlock || e.kind() == ErrorKind::TimedOut => break Ok("kept alive"),
            Err(e) => break Err(e),
        }
    };
    let status = String::from_utf8_lossy(&got).lines().next().unwrap_or_default().to_owned();
    (status, late, end)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn answers_before_the_body_do_not_reset_the_connection() {
    let mut cfg = Config::default();
    cfg.server.data_dir = common::http_test_dir("early-answer");
    let dir = cfg.server.data_dir.clone();
    let mut conn = db::open(&cfg.db_path()).unwrap();
    db::migrate(&mut conn).unwrap();
    let state = app::Shared::new(conn, cfg).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let running = state.clone();
    let server = tokio::spawn(async move {
        axum::serve(listener, app::router(running).into_make_service_with_connect_info::<std::net::SocketAddr>())
            .await
            .unwrap()
    });
    let results = tokio::task::spawn_blocking(move || {
        let cases = [
            // no caller: 401 without looking at the body
            ("POST", "/api/v1/devices/invite", "application/json", "", 20_000, "401"),
            // a bad caller
            ("POST", "/api/v1/devices/invite", "application/json", "Authorization: Bearer nope\r\n", 20_000, "401"),
            // no such route: 404 from the router
            ("POST", "/api/v1/no-such-route", "application/json", "", 20_000, "404"),
            // not JSON: 415 before the body
            ("POST", "/api/v1/auth/session", "text/plain", "", 20_000, "415"),
            // over the 2 MiB JSON limit: 413 after reading part of it
            ("POST", "/api/v1/auth/session", "application/json", "", 3 << 20, "413"),
        ];
        cases
            .iter()
            .map(|&(method, path, ct, extra, len, want)| {
                let head = format!(
                    "{method} {path} HTTP/1.1\r\nHost: t\r\nContent-Type: {ct}\r\nContent-Length: {len}\r\n{extra}\r\n"
                );
                (format!("{method} {path} ({ct}, {len} bytes)"), want, early(addr, &head, len))
            })
            .collect::<Vec<_>>()
    })
    .await
    .unwrap();
    server.abort();
    let _ = server.await;
    common::release(state, &dir).await;
    let mut problems = Vec::new();
    for (what, want, (status, late, end)) in results {
        if !status.contains(want) {
            problems.push(format!("{what}: wanted {want}, got {status:?} ({end:?})"));
        } else if let Err(e) = late {
            problems.push(format!("{what}: answered {status:?} and stopped reading: the rest of the body met {e}"));
        } else if let Err(e) = end {
            problems.push(format!("{what}: answered {status:?}, then the connection was reset ({e})"));
        }
    }
    assert!(problems.is_empty(), "{}", problems.join("\n"));
}
