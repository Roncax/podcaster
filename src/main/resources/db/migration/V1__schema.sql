create table shows (
    id bigserial primary key,
    name varchar(200) not null,
    slug varchar(100) not null unique,
    description text,
    language varchar(20) not null,
    voice_id varchar(200) not null,
    length_scale double precision not null default 1.0,
    writer_model varchar(100) not null,
    ranker_model varchar(100),
    focus_prompt text,
    target_duration_minutes int not null default 20,
    min_items int not null default 3,
    cron varchar(100),
    enabled boolean not null default true,
    retain_episodes int not null default 30,
    feed_token varchar(100),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table sources (
    id bigserial primary key,
    show_id bigint not null references shows(id) on delete cascade,
    connector_type varchar(100) not null,
    config jsonb not null default '{}',
    fetch_full_text boolean not null default true,
    enabled boolean not null default true,
    last_fetched_at timestamptz,
    last_error text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table runs (
    id bigserial primary key,
    show_id bigint not null references shows(id) on delete cascade,
    trigger varchar(20) not null,
    stage varchar(20) not null,
    status varchar(20) not null,
    since timestamptz not null,
    attempt int not null default 1,
    error text,
    started_at timestamptz not null default now(),
    finished_at timestamptz
);
create unique index runs_one_active_per_show on runs(show_id) where status = 'RUNNING';
create index runs_show_started on runs(show_id, started_at desc);

create table episodes (
    id bigserial primary key,
    run_id bigint not null unique references runs(id) on delete cascade,
    show_id bigint not null references shows(id) on delete cascade,
    title varchar(300),
    description text,
    selection jsonb,
    outline jsonb,
    script_parts jsonb,
    script text,
    audio_path varchar(500),
    duration_seconds double precision,
    size_bytes bigint,
    published_at timestamptz,
    created_at timestamptz not null default now()
);
create index episodes_show_published on episodes(show_id, published_at desc);

create table items (
    id bigserial primary key,
    show_id bigint not null references shows(id) on delete cascade,
    source_id bigint not null references sources(id) on delete cascade,
    url varchar(2000) not null,
    content_hash varchar(64) not null,
    title varchar(1000) not null,
    author varchar(300),
    published_at timestamptz,
    fetched_at timestamptz not null,
    summary text,
    full_text text,
    used_in_episode_id bigint references episodes(id) on delete set null,
    unique (show_id, url)
);
create index items_show_hash on items(show_id, content_hash);
create index items_show_unused on items(show_id) where used_in_episode_id is null;

create table voice_calibrations (
    id bigserial primary key,
    voice_id varchar(200) not null,
    length_scale double precision not null,
    words_per_minute double precision not null,
    samples int not null default 0,
    unique (voice_id, length_scale)
);
