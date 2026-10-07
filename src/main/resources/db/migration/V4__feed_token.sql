-- Feed and media URLs carry a per-show secret so the feed can be exposed publicly.
update shows set feed_token = replace(gen_random_uuid()::text, '-', '') where feed_token is null;
alter table shows alter column feed_token set not null;
alter table shows add constraint shows_feed_token_key unique (feed_token);
