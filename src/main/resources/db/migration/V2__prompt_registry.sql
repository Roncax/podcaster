create table prompts (
    key varchar(50) primary key,
    description text not null
);

create table prompt_versions (
    id bigserial primary key,
    prompt_key varchar(50) not null references prompts(key),
    version int not null,
    body text not null,
    note text,
    created_at timestamptz not null default now(),
    unique (prompt_key, version)
);

create table prompt_labels (
    id bigserial primary key,
    prompt_key varchar(50) not null references prompts(key),
    label varchar(20) not null check (label in ('production', 'draft')),
    version_id bigint not null references prompt_versions(id),
    unique (prompt_key, label)
);

create table show_prompt_overrides (
    id bigserial primary key,
    show_id bigint not null references shows(id) on delete cascade,
    prompt_key varchar(50) not null references prompts(key),
    version_id bigint not null references prompt_versions(id),
    unique (show_id, prompt_key)
);

alter table episodes add column prompt_versions jsonb;
