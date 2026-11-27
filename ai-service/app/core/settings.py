from pydantic import AliasChoices, Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_", extra="ignore")
    internal_token: SecretStr = Field(min_length=32)
    model_api_key: SecretStr | None = None
    model_name: str = "deepseek-chat"
    request_timeout_seconds: float = Field(default=30, gt=0, le=120)
    backend_url: str = Field(default='http://127.0.0.1:8080',validation_alias=AliasChoices('BACKEND_URL','AI_BACKEND_URL','backend_url'))
    process_max_bytes: int = Field(default=50*1024*1024, gt=0, le=50*1024*1024)
    process_max_expanded: int = Field(default=200*1024*1024, gt=0, le=200*1024*1024)
    process_max_zip_items: int = Field(default=5000, gt=0, le=5000)
    process_max_ratio: int = Field(default=200, gt=0, le=200)
    process_download_timeout_seconds: float = Field(default=60, gt=0, le=120)
    process_timeout_seconds: float = Field(default=600, gt=0, le=900)
