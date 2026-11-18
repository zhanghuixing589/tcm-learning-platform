from fastapi.testclient import TestClient
from pydantic import ValidationError
import pytest
from app.main import create_app
from app.core.settings import Settings

TOKEN = "test-internal-credential-12345678901234567890"


@pytest.fixture
def client():
    return TestClient(create_app(Settings(_env_file=None, internal_token=TOKEN)))


def test_internal_health_requires_service_credential(client):
    assert client.get("/internal/v1/health/liveness").status_code == 401
    assert client.get("/internal/v1/health/liveness", headers={"X-Internal-Token": "incorrect"}).status_code == 401


def test_liveness_and_readiness_work_without_model(client):
    for path in ("liveness", "readiness"):
        response = client.get(f"/internal/v1/health/{path}", headers={"X-Internal-Token": TOKEN})
        assert response.status_code == 200
        assert response.json()["data"]["status"] == "UP"
        assert response.headers["X-Trace-Id"] == response.json()["traceId"]


def test_missing_model_is_explicit_and_no_secret_is_returned(client):
    response = client.get("/internal/v1/capabilities", headers={"X-Internal-Token": TOKEN})
    assert response.json()["data"]["generation"] == {"available": False, "code": "MODEL_NOT_CONFIGURED"}
    assert TOKEN not in response.text


def test_short_internal_credential_fails_configuration():
    with pytest.raises(ValidationError):
        Settings(_env_file=None, internal_token="short")


def test_trace_header_propagates_only_valid_identifier(client):
    valid = "trace-acceptance-123"
    response = client.get("/internal/v1/health/liveness", headers={"X-Internal-Token": TOKEN, "X-Trace-Id": valid})
    assert response.json()["traceId"] == valid


def test_configured_key_does_not_claim_generation_is_implemented():
    client = TestClient(create_app(Settings(_env_file=None, internal_token=TOKEN, model_api_key="test-key")))
    response = client.get("/internal/v1/capabilities", headers={"X-Internal-Token": TOKEN})
    assert response.json()["data"]["generation"] == {"available": False, "code": "NOT_IMPLEMENTED"}
