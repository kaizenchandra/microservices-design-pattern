create table projection_head(id int primary key check(id=1),generation uuid not null);
insert into projection_head values(1,'00000000-0000-0000-0000-000000000001');
create table event_archive(position bigserial unique,event_id uuid primary key,order_id uuid not null,version bigint not null,body jsonb not null,occurred_at timestamptz not null,received_at timestamptz not null default now(),unique(order_id,version));
create index archive_order on event_archive(order_id,version);
create table order_view(generation uuid not null,order_id uuid not null,customer text not null,version bigint not null,body jsonb not null,projected_at timestamptz not null default now(),primary key(generation,order_id));
create index order_customer on order_view(generation,customer,order_id);
