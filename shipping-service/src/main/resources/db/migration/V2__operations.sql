create table operation
(
    order_id     uuid primary key,
    state        text        not null,
    aborted      boolean     not null default false,
    version      bigint      not null default 0,
    body         jsonb       not null,
    attempts     int         not null default 0,
    next_attempt timestamptz not null default now(),
    created_at   timestamptz not null default now()
);
create index operation_due on operation (next_attempt) where state in ('PENDING','UNKNOWN','COMPENSATING');
