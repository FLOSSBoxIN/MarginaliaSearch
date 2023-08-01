package nu.marginalia.control.svc;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zaxxer.hikari.HikariDataSource;
import nu.marginalia.control.model.Actor;
import nu.marginalia.control.model.MessageQueueEntry;
import nu.marginalia.mqsm.graph.AbstractStateGraph;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class MessageQueueViewService {

    private final HikariDataSource dataSource;

    @Inject
    public MessageQueueViewService(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<MessageQueueEntry> getLastEntries(int n) {
        try (var conn = dataSource.getConnection();
             var query = conn.prepareStatement("""
                SELECT ID, RELATED_ID, SENDER_INBOX, RECIPIENT_INBOX, FUNCTION, PAYLOAD, OWNER_INSTANCE, OWNER_TICK, STATE, CREATED_TIME, UPDATED_TIME, TTL
                FROM MESSAGE_QUEUE
                ORDER BY ID DESC
                LIMIT ?
                """)) {

            query.setInt(1, n);
            List<MessageQueueEntry> entries = new ArrayList<>(n);
            var rs = query.executeQuery();
            while (rs.next()) {
                entries.add(new MessageQueueEntry(
                        rs.getLong("ID"),
                        rs.getLong("RELATED_ID"),
                        rs.getString("SENDER_INBOX"),
                        rs.getString("RECIPIENT_INBOX"),
                        rs.getString("FUNCTION"),
                        rs.getString("PAYLOAD"),
                        rs.getString("OWNER_INSTANCE"),
                        rs.getLong("OWNER_TICK"),
                        rs.getString("STATE"),
                        rs.getTimestamp("CREATED_TIME").toLocalDateTime().toLocalTime().toString(),
                        rs.getTimestamp("UPDATED_TIME").toLocalDateTime().toLocalTime().toString(),
                        rs.getInt("TTL")
                ));
            }
            return entries;
        }
        catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public MessageQueueEntry getMessage(long id) {
        try (var conn = dataSource.getConnection();
             var query = conn.prepareStatement("""
                SELECT ID, RELATED_ID, SENDER_INBOX, RECIPIENT_INBOX, FUNCTION, PAYLOAD, OWNER_INSTANCE, OWNER_TICK, STATE, CREATED_TIME, UPDATED_TIME, TTL
                FROM MESSAGE_QUEUE
                WHERE ID=?
                """)) {

            query.setLong(1, id);

            var rs = query.executeQuery();
            if (rs.next()) {
                return new MessageQueueEntry(
                        rs.getLong("ID"),
                        rs.getLong("RELATED_ID"),
                        rs.getString("SENDER_INBOX"),
                        rs.getString("RECIPIENT_INBOX"),
                        rs.getString("FUNCTION"),
                        rs.getString("PAYLOAD"),
                        rs.getString("OWNER_INSTANCE"),
                        rs.getLong("OWNER_TICK"),
                        rs.getString("STATE"),
                        rs.getTimestamp("CREATED_TIME").toLocalDateTime().toLocalTime().toString(),
                        rs.getTimestamp("UPDATED_TIME").toLocalDateTime().toLocalTime().toString(),
                        rs.getInt("TTL")
                );
            }
        }
        catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }

    public Object getLastEntriesForInbox(String inbox, int n) {
        try (var conn = dataSource.getConnection();
             var query = conn.prepareStatement("""
                SELECT ID, RELATED_ID, SENDER_INBOX, RECIPIENT_INBOX, FUNCTION, PAYLOAD, OWNER_INSTANCE, OWNER_TICK, STATE, CREATED_TIME, UPDATED_TIME, TTL
                FROM MESSAGE_QUEUE
                WHERE RECIPIENT_INBOX=?
                ORDER BY ID DESC
                LIMIT ?
                """)) {

            query.setString(1, inbox);
            query.setInt(2, n);
            List<MessageQueueEntry> entries = new ArrayList<>(n);
            var rs = query.executeQuery();
            while (rs.next()) {
                entries.add(new MessageQueueEntry(
                        rs.getLong("ID"),
                        rs.getLong("RELATED_ID"),
                        rs.getString("SENDER_INBOX"),
                        rs.getString("RECIPIENT_INBOX"),
                        rs.getString("FUNCTION"),
                        rs.getString("PAYLOAD"),
                        rs.getString("OWNER_INSTANCE"),
                        rs.getLong("OWNER_TICK"),
                        rs.getString("STATE"),
                        rs.getTimestamp("CREATED_TIME").toLocalDateTime().toLocalTime().toString(),
                        rs.getTimestamp("UPDATED_TIME").toLocalDateTime().toLocalTime().toString(),
                        rs.getInt("TTL")
                ));
            }
            return entries;
        }
        catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }
}
