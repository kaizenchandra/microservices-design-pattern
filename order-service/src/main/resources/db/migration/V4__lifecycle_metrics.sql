alter table order_stream
    add column terminal boolean not null default false;
update order_stream s
set terminal= true
where exists(select 1 from domain_event e where e.aggregate_id = s.id and e.type in ('Confirmed', 'Compensated'));
