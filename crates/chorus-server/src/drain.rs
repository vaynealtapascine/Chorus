//! Take unread request bodies off the wire (R35).
//!
//! Many answers come before the body is read: 401 from a handler that authenticates first, 415
//! from `Json` on a wrong content type, 429 from the rate limiter. hyper then closes the connection
//! with the rest of the body still on its way, and the late bytes reach a closed socket, which
//! the TCP stack answers with a reset. On Windows a reset also throws away the answer the client
//! had already received, so the client sees "connection aborted" (os error 10053/10054) instead of
//! the 401, and a web app takes an expired session for being offline.
//!
//! This layer keeps a handle on each request body. When the handler has answered and let go of a
//! body it did not finish, the rest is read and thrown away in the background, up to [`CAP`] bytes
//! and for at most [`PATIENCE`], so the connection ends cleanly (or stays open for the next request).

use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll};
use std::time::Duration;

use axum::body::{Body, Bytes, HttpBody};
use axum::extract::Request;
use axum::middleware::Next;
use axum::response::Response;
use futures_util::StreamExt;

/// The most a left-over body is read for; beyond it the connection is simply closed. Above every
/// route's body limit (the largest is 4 MiB), so any body a route could accept is drained.
pub const CAP: u64 = 8 * 1024 * 1024;
/// How long a client gets to send the rest.
pub const PATIENCE: Duration = Duration::from_secs(10);

type Shared = Arc<Mutex<Body>>;

/// The body the handler sees: the request's own, reached through a handle this layer keeps too.
struct Watched(Shared);

impl HttpBody for Watched {
    type Data = Bytes;
    type Error = axum::Error;

    fn poll_frame(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<http_body::Frame<Bytes>, axum::Error>>> {
        let mut body = self.0.lock().unwrap_or_else(|e| e.into_inner());
        Pin::new(&mut *body).poll_frame(cx)
    }

    fn is_end_stream(&self) -> bool {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).is_end_stream()
    }

    fn size_hint(&self) -> http_body::SizeHint {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).size_hint()
    }
}

pub async fn layer(req: Request, next: Next) -> Response {
    if req.body().is_end_stream() || req.body().size_hint().lower() > CAP {
        return next.run(req).await;
    }
    let (parts, body) = req.into_parts();
    let shared: Shared = Arc::new(Mutex::new(body));
    let res = next.run(Request::from_parts(parts, Body::new(Watched(shared.clone())))).await;
    // the handler let go of the body (it may still hold it, e.g. streaming it somewhere)
    if let Ok(body) = Arc::try_unwrap(shared) {
        let body = body.into_inner().unwrap_or_else(|e| e.into_inner());
        if !body.is_end_stream() {
            tokio::spawn(async move {
                let mut rest = body.into_data_stream();
                let mut read = 0u64;
                let _ = tokio::time::timeout(PATIENCE, async {
                    while let Some(Ok(chunk)) = rest.next().await {
                        read += chunk.len() as u64;
                        if read > CAP {
                            break;
                        }
                    }
                })
                .await;
            });
        }
    }
    res
}
