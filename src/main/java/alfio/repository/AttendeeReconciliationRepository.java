/*
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.repository;

import ch.digitalfondue.npjt.Bind;
import ch.digitalfondue.npjt.Query;
import ch.digitalfondue.npjt.QueryRepository;
import ch.digitalfondue.npjt.ConstructorAnnotationRowMapper.Column;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@QueryRepository
public interface AttendeeReconciliationRepository {
    record Stored(@Column("id") String id, @Column("created_at") ZonedDateTime createdAt,
                  @Column("expires_at") ZonedDateTime expiresAt, @Column("encrypted_result") String encryptedResult, @Column("summary_json") String summaryJson) {}

    record TicketSnapshot(@Column("uuid") String uuid, @Column("email_address") String email,
                          @Column("status") String status, @Column("tickets_reservation_id") String reservationId) {}

    @Query("select t.uuid,t.email_address,t.status,t.tickets_reservation_id from ticket t join tickets_reservation r on t.tickets_reservation_id=r.id where t.event_id=:eventId and t.status in (" + TicketRepository.CONFIRMED + ") order by t.id limit 20001")
    List<TicketSnapshot> tickets(@Bind("eventId") int eventId);

    @Query("select 1 from pg_advisory_xact_lock(252525)")
    Integer lockCreation();

    @Query("delete from attendee_reconciliation where expires_at <= current_timestamp")
    int deleteExpired();

    @Query("delete from attendee_ai_quota where window_start < current_timestamp - interval '2 hours'")
    int deleteOldQuotas();

    @Query("select count(*) from attendee_reconciliation where event_id_fk=:eventId and created_by=:owner and expires_at > current_timestamp")
    Integer countForOwner(@Bind("eventId") int eventId, @Bind("owner") String owner);

    @Query("select coalesce(sum(octet_length(encrypted_result)), 0) from attendee_reconciliation where expires_at > current_timestamp")
    Long totalBytes();

    @Query("insert into attendee_reconciliation(id,event_id_fk,created_by,expires_at,encrypted_result,summary_json) values(:id,:eventId,:owner,current_timestamp + interval '24 hours',:payload,:summary)")
    int insert(@Bind("id") String id, @Bind("eventId") int eventId, @Bind("owner") String owner, @Bind("payload") String payload, @Bind("summary") String summary);

    @Query("select id,created_at,expires_at,encrypted_result,summary_json from attendee_reconciliation where id=:id and event_id_fk=:eventId and created_by=:owner and expires_at > current_timestamp")
    Optional<Stored> find(@Bind("id") String id, @Bind("eventId") int eventId, @Bind("owner") String owner);

    @Query("select id,created_at,expires_at,'' as encrypted_result,summary_json from attendee_reconciliation where event_id_fk=:eventId and created_by=:owner and expires_at > current_timestamp order by created_at desc limit 10")
    List<Stored> list(@Bind("eventId") int eventId, @Bind("owner") String owner);

    @Query("delete from attendee_reconciliation where id=:id and event_id_fk=:eventId and created_by=:owner")
    int delete(@Bind("id") String id, @Bind("eventId") int eventId, @Bind("owner") String owner);

    @Query("update attendee_reconciliation set ai_locked_until=current_timestamp + interval '45 seconds' where id=:id and event_id_fk=:eventId and created_by=:owner and expires_at > current_timestamp and (ai_locked_until is null or ai_locked_until <= current_timestamp)")
    int acquireAi(@Bind("id") String id, @Bind("eventId") int eventId, @Bind("owner") String owner);

    @Query("update attendee_reconciliation set ai_locked_until=null where id=:id and event_id_fk=:eventId and created_by=:owner")
    int releaseAi(@Bind("id") String id, @Bind("eventId") int eventId, @Bind("owner") String owner);

    @Query("""
        insert into attendee_ai_quota(scope, window_start, calls) values(:scope,date_trunc('hour',current_timestamp),1)
        on conflict(scope,window_start) do update set calls=attendee_ai_quota.calls+1
        where attendee_ai_quota.calls < :maximum
        """)
    int reserveQuota(@Bind("scope") String scope, @Bind("maximum") int maximum);
}
