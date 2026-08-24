-- Account 컨텍스트는 로그인 식별자, 역할, 계정 상태만 소유한다.
-- 강사 표시 이름 같은 업무 프로필은 아래 teacher_profiles로 분리한다.
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    email VARCHAR(320) NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    withdrawn_at TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,

    CONSTRAINT ck_accounts_email
        CHECK (
            email = btrim(email)
            AND char_length(email) BETWEEN 1 AND 320
        ),
    CONSTRAINT ck_accounts_role
        CHECK (role IN ('TEACHER', 'PARENT', 'STUDENT')),
    CONSTRAINT ck_accounts_status
        CHECK (status IN ('ACTIVE', 'WITHDRAWN', 'SUSPENDED')),
    CONSTRAINT ck_accounts_withdrawn_at
        CHECK (
            (status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL)
            OR (status <> 'WITHDRAWN' AND withdrawn_at IS NULL)
        )
);

-- 애플리케이션은 이메일을 소문자로 정규화하지만, 동시 요청이나 직접 SQL도
-- 우회하지 못하도록 PostgreSQL에서도 대소문자를 무시한 유일성을 보장한다.
CREATE UNIQUE INDEX uq_accounts_email_case_insensitive
    ON accounts (lower(email));

-- 비밀번호 원문이 Account 엔티티에 섞이지 않도록 자격정보를 별도 테이블로 격리한다.
-- account_id가 PK이면서 FK이므로 로컬 비밀번호 자격정보는 계정당 최대 하나다.
CREATE TABLE account_password_credentials (
    account_id UUID PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    password_algorithm VARCHAR(30) NOT NULL,
    password_changed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_account_password_credentials_account
        FOREIGN KEY (account_id)
        REFERENCES accounts (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_account_password_credentials_hash
        CHECK (btrim(password_hash) <> ''),
    CONSTRAINT ck_account_password_credentials_algorithm
        CHECK (password_algorithm IN ('BCRYPT'))
);

-- TeacherProfile은 Roster 컨텍스트의 업무 정보다.
-- Account 생성과 같은 트랜잭션에서 저장해 계정이나 프로필만 남는 상태를 방지한다.
CREATE TABLE teacher_profiles (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    account_id UUID NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_teacher_profiles_account
        FOREIGN KEY (account_id)
        REFERENCES accounts (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_teacher_profiles_account
        UNIQUE (account_id),
    CONSTRAINT ck_teacher_profiles_display_name
        CHECK (
            display_name = btrim(display_name)
            AND char_length(display_name) BETWEEN 1 AND 80
        ),
    CONSTRAINT ck_teacher_profiles_updated_at
        CHECK (updated_at >= created_at)
);
