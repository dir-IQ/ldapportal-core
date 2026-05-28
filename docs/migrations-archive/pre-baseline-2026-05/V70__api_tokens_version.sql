-- V70: add optimistic-lock version column to api_tokens so concurrent
-- rotate/revoke operations on the same row fail fast with
-- OptimisticLockingFailureException (mapped to 409 by GlobalExceptionHandler).

ALTER TABLE api_tokens ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
