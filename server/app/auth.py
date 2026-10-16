"""Shared-secret check. The phone sends Authorization: Bearer <key>."""

from __future__ import annotations

import hashlib
import hmac

from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

_bearer = HTTPBearer(auto_error=False)


def keys_match(presented: str, expected: str) -> bool:
    if not presented or not expected:
        return False
    return hmac.compare_digest(
        hashlib.sha256(presented.encode("utf-8")).digest(),
        hashlib.sha256(expected.encode("utf-8")).digest(),
    )


def presented_key(request: Request, creds: HTTPAuthorizationCredentials | None) -> str:
    if creds is not None and creds.scheme.lower() == "bearer":
        return creds.credentials.strip()
    return request.headers.get("x-api-key", "").strip()


async def require_api_key(
    request: Request,
    creds: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> None:
    settings = request.app.state.settings
    if keys_match(presented_key(request, creds), settings.api_key):
        return
    raise HTTPException(
        status_code=401,
        detail=(
            "The API key was rejected. On the PC, open config/.env and copy the "
            "value after API_KEY= into the phone."
        ),
        headers={"WWW-Authenticate": "Bearer"},
    )
