--
-- This file is part of alf.io.
--
-- alf.io is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.
--
-- alf.io is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU General Public License for more details.
--
-- You should have received a copy of the GNU General Public License
-- along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
--

create table attendee_reconciliation (
    id varchar(36) primary key,
    event_id_fk integer not null references event(id) on delete cascade,
    created_by varchar(255) not null,
    created_at timestamp with time zone not null default current_timestamp,
    expires_at timestamp with time zone not null,
    encrypted_result text not null,
    summary_json text not null,
    ai_locked_until timestamp with time zone
);
create index attendee_reconciliation_owner_idx on attendee_reconciliation(event_id_fk, created_by, created_at desc);
create index attendee_reconciliation_expiry_idx on attendee_reconciliation(expires_at);

create table attendee_ai_quota (
    scope varchar(80) not null,
    window_start timestamp with time zone not null,
    calls integer not null check (calls > 0),
    primary key (scope, window_start)
);
