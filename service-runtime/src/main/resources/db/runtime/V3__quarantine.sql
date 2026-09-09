alter table outbox add column topic text not null default 'fulfillment.v1';
create table quarantine(partition_id int not null,position bigint not null,body text not null,reason text not null,approved_skip boolean not null default false,created_at timestamptz not null default now(),primary key(partition_id,position));
