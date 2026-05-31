CREATE TABLE submissions (
    id           SERIAL PRIMARY KEY,
    facility_id  INTEGER      NOT NULL REFERENCES facilities(id),
    agent_bank   VARCHAR(255) NOT NULL,
    period_month VARCHAR(20)  NOT NULL,
    status       VARCHAR(50)  NOT NULL DEFAULT 'Processing',
    file_name    VARCHAR(255) NOT NULL,
    file_path    VARCHAR(512),
    uploaded_by  INTEGER REFERENCES users(id),
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
