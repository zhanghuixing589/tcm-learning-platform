from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_", extra="ignore")
    internal_token: SecretStr = Field(min_length=32)
    model_api_key: SecretStr | None = None
    model_name: str = "deepseek-chat"
    request_timeout_seconds: float = Field(default=30, gt=0, le=120)
