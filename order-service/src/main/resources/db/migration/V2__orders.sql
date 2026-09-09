create table order_stream(id uuid primary key,version bigint not null default 0,created_at timestamptz not null default now(),deadline timestamptz not null default now()+interval '120 seconds',closed boolean not null default false);
create table domain_event(aggregate_id uuid not null references order_stream(id),version bigint not null,type text not null,schema_version int not null,body jsonb not null,occurred_at timestamptz not null default now(),primary key(aggregate_id,version));
create function immutable_event() returns trigger language plpgsql as $$ begin raise exception 'domain events are immutable'; end $$;
create trigger immutable_event before update or delete on domain_event for each row execute function immutable_event();
create table order_snapshot(aggregate_id uuid primary key,version bigint not null,schema_version int not null,body jsonb not null);
create table command_key(customer text not null,key text not null,fingerprint text not null,order_id uuid not null references order_stream(id),primary key(customer,key));
create index order_deadlines on order_stream(deadline) where not closed;
