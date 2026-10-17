-- Per-show prompts: show_id null = default prompt, otherwise that show's own copy.
alter table prompt_versions add column show_id bigint references shows(id) on delete cascade;
alter table prompt_versions drop constraint prompt_versions_prompt_key_version_key;
alter table prompt_versions add constraint prompt_versions_key_show_version_key
    unique nulls not distinct (prompt_key, show_id, version);

-- Fork every existing show from its pinned version, else from the production label.
insert into prompt_versions (prompt_key, show_id, version, body, note, created_at)
select p.key, s.id, 1, src.body, 'Forked from default v' || src.version, now()
from shows s
cross join prompts p
cross join lateral (
    select v.body, v.version from prompt_versions v
    where v.id = coalesce(
        (select o.version_id from show_prompt_overrides o where o.show_id = s.id and o.prompt_key = p.key),
        (select l.version_id from prompt_labels l where l.prompt_key = p.key and l.label = 'production'))
) src;

-- Defaults must end on the production body: "latest default" seeds new shows and Reset to default.
-- Where production is not the highest default version (unpromoted draft, rollback), append a copy of it.
insert into prompt_versions (prompt_key, show_id, version, body, note, created_at)
select l.prompt_key, null, m.max + 1, v.body, 'Production at migration (v' || v.version || ')', now()
from prompt_labels l
join prompt_versions v on v.id = l.version_id
join (select prompt_key, max(version) as max from prompt_versions where show_id is null group by prompt_key) m
  on m.prompt_key = l.prompt_key
where l.label = 'production' and v.version < m.max;

-- Labels now belong to shows only.
delete from prompt_labels;
alter table prompt_labels add column show_id bigint not null references shows(id) on delete cascade;
alter table prompt_labels drop constraint prompt_labels_prompt_key_label_key;
alter table prompt_labels add constraint prompt_labels_show_key_label_key unique (show_id, prompt_key, label);

insert into prompt_labels (prompt_key, label, version_id, show_id)
select v.prompt_key, l.label, v.id, v.show_id
from prompt_versions v
cross join (values ('production'), ('draft')) as l(label)
where v.show_id is not null;

drop table show_prompt_overrides;

-- Lineage of older episodes refers to the global registry.
alter table episodes add column prompt_versions_global boolean not null default false;
update episodes set prompt_versions_global = true;
