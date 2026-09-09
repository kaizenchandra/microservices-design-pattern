create table stock
(
    sku       varchar(40) primary key,
    available integer not null check (available >= 0),
    version   bigint  not null default 0
);
insert into stock
values ('DEMO', 10000, 0),
       ('LAST', 1, 0),
       ('EMPTY', 0, 0);
create table reservation
(
    order_id   uuid primary key,
    sku        text,
    quantity   int check (quantity > 0),
    state      text        not null,
    version    bigint      not null default 0,
    body       jsonb       not null,
    expires_at timestamptz not null default now() + interval '90 seconds'
);
create index reservation_expiry on reservation (expires_at) where state='RESERVED';
