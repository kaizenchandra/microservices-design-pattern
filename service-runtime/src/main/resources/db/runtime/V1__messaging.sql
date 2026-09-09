create table outbox
(
    id           bigserial primary key,
    event_id     uuid unique not null,
    aggregate_id uuid        not null,
    body         jsonb       not null,
    published_at timestamptz,
    attempts     int         not null default 0,
    next_attempt timestamptz not null default now(),
    created_at   timestamptz not null default now()
);
create index outbox_pending on outbox (aggregate_id, id) where published_at is null;
create table inbox
(
    event_id    uuid primary key,
    received_at timestamptz not null
);
create table consumer_position
(
    partition_id int primary key,
    position     bigint      not null,
    updated_at   timestamptz not null
);
