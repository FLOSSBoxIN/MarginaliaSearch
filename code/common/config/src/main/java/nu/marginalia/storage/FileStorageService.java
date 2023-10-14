package nu.marginalia.storage;

import com.google.inject.name.Named;
import com.zaxxer.hikari.HikariDataSource;
import lombok.SneakyThrows;
import nu.marginalia.storage.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Manages file storage for processes and services
 */
@Singleton
public class FileStorageService {
    private final HikariDataSource dataSource;
    private final int node;
    private final Logger logger = LoggerFactory.getLogger(FileStorageService.class);

    private static final DateTimeFormatter dirNameDatePattern = DateTimeFormatter.ofPattern("__uu-MM-dd'T'HH_mm_ss.SSS"); // filesystem safe ISO8601

    public Optional<FileStorage> findFileStorageToDelete() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                SELECT ID FROM FILE_STORAGE WHERE STATE='DELETE' LIMIT 1
                """)) {
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(getStorage(new FileStorageId(rs.getLong(1))));
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    public Set<Integer> getConfiguredNodes() {
        Set<Integer> ret = new HashSet<>();

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                SELECT DISTINCT(NODE) FROM FILE_STORAGE_BASE
                """)) {
            var rs = stmt.executeQuery();
            while (rs.next()) {
                ret.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            logger.warn("SQL error getting nodes", e);
        }

        return ret;
    }

    @Inject
    public FileStorageService(HikariDataSource dataSource, @Named("wmsa-system-node") Integer node) {
        this.dataSource = dataSource;
        this.node = node;

        for (var type : FileStorageType.values()) {
            String overrideProperty = System.getProperty(type.overrideName());

            if (overrideProperty == null || overrideProperty.isBlank())
                continue;

            logger.info("FileStorage override present: {} -> {}", type,
                    FileStorage.createOverrideStorage(type, FileStorageBaseType.CURRENT, overrideProperty).asPath());
        }
    }

    /** @return the storage base with the given id, or null if it does not exist */
    public FileStorageBase getStorageBase(FileStorageBaseId type) throws SQLException  {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                     SELECT ID, NAME, PATH, TYPE
                     FROM FILE_STORAGE_BASE WHERE ID = ?
                     """)) {
            stmt.setLong(1, type.id());
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new FileStorageBase(
                            new FileStorageBaseId(rs.getLong(1)),
                            FileStorageBaseType.valueOf(rs.getString(4)),
                            rs.getString(2),
                            rs.getString(3)
                    );
                }
            }
        }
        return null;
    }

    public void synchronizeStorageManifests(FileStorageBase base) {
        Set<String> ignoredPaths = new HashSet<>();

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                SELECT PATH FROM FILE_STORAGE WHERE BASE_ID = ?
                """)) {
            stmt.setLong(1, base.id().id());
            var rs = stmt.executeQuery();
            while (rs.next()) {
                ignoredPaths.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        File basePathFile = Path.of(base.path()).toFile();
        File[] files = basePathFile.listFiles(pathname -> pathname.isDirectory() && !ignoredPaths.contains(pathname.getName()));
        if (files == null) return;
        for (File file : files) {
            var maybeManifest = FileStorageManifest.find(file.toPath());
            if (maybeManifest.isEmpty()) continue;
            var manifest = maybeManifest.get();

            logger.info("Discovered new file storage: " + file.getName() + " (" + manifest.type() + ")");

            try (var conn = dataSource.getConnection();
                 var stmt = conn.prepareStatement("""
                    INSERT INTO FILE_STORAGE(BASE_ID, PATH, TYPE, DESCRIPTION)
                    VALUES (?, ?, ?, ?)
                    """)) {
                stmt.setLong(1, base.id().id());
                stmt.setString(2, file.getName());
                stmt.setString(3, manifest.type().name());
                stmt.setString(4, manifest.description());
                stmt.execute();
                conn.commit();

            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void relateFileStorages(FileStorageId source, FileStorageId target) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                INSERT INTO FILE_STORAGE_RELATION(SOURCE_ID, TARGET_ID) VALUES (?, ?)
                """)) {
            stmt.setLong(1, source.id());
            stmt.setLong(2, target.id());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<FileStorage> getSourceFromStorage(FileStorage storage) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                     SELECT SOURCE_ID FROM FILE_STORAGE_RELATION WHERE TARGET_ID = ?
                     """)) {
            stmt.setLong(1, storage.id().id());
            var rs = stmt.executeQuery();
            List<FileStorage> ret = new ArrayList<>();
            while (rs.next()) {
                ret.add(getStorage(new FileStorageId(rs.getLong(1))));
            }
            return ret;
        }
    }

    public List<FileStorage> getTargetFromStorage(FileStorage storage) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                     SELECT TARGET_ID FROM FILE_STORAGE_RELATION WHERE SOURCE_ID = ?
                     """)) {
            stmt.setLong(1, storage.id().id());
            var rs = stmt.executeQuery();
            List<FileStorage> ret = new ArrayList<>();
            while (rs.next()) {
                ret.add(getStorage(new FileStorageId(rs.getLong(1))));
            }
            return ret;
        }
    }

    /** @return the storage base with the given type, or null if it does not exist */
    public FileStorageBase getStorageBase(FileStorageBaseType type) throws SQLException {
        return getStorageBase(type, node);
    }

    public FileStorageBase getStorageBase(FileStorageBaseType type, int node) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                     SELECT ID, NAME, PATH, TYPE
                     FROM FILE_STORAGE_BASE WHERE TYPE = ? AND NODE = ?
                     """)) {
            stmt.setString(1, type.name());
            stmt.setInt(2, node);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new FileStorageBase(
                            new FileStorageBaseId(rs.getLong(1)),
                            FileStorageBaseType.valueOf(rs.getString(4)),
                            rs.getString(2),
                            rs.getString(3)
                    );
                }
            }
        }
        return null;
    }
    public FileStorageBase createStorageBase(String name, Path path, FileStorageBaseType type) throws SQLException, FileNotFoundException {

        if (!Files.exists(path)) {
            throw new FileNotFoundException("Storage base path does not exist: " + path);
        }

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                     INSERT INTO FILE_STORAGE_BASE(NAME, PATH, TYPE, NODE)
                     VALUES (?, ?, ?, ?)
                     """)) {
            stmt.setString(1, name);
            stmt.setString(2, path.toString());
            stmt.setString(3, type.name());
            stmt.setInt(4, node);

            int update = stmt.executeUpdate();
            if (update < 0) {
                throw new SQLException("Failed to create storage base");
            }
        }

        return getStorageBase(type);
    }

    @SneakyThrows
    private Path allocateDirectory(Path basePath, String prefix) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        String timestampPart = now.format(dirNameDatePattern);
        Path maybePath = basePath.resolve(prefix + timestampPart);

        try {
            Files.createDirectory(maybePath,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x"))
            );
        }
        catch (FileAlreadyExistsException ex) {
            // in case of a race condition, try again with some random cruft at the end
            maybePath = basePath.resolve(prefix + timestampPart + "_" + Long.toHexString(ThreadLocalRandom.current().nextLong()));

            Files.createDirectory(maybePath,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x"))
            );
        }

        return maybePath;
    }

    /** Allocate a temporary storage of the given type if temporary allocation is permitted */
    public FileStorage allocateTemporaryStorage(FileStorageBase base,
                                                FileStorageType type,
                                                String prefix,
                                                String description) throws IOException, SQLException
    {
        Path newDir = allocateDirectory(base.asPath(), prefix);

        String relDir = base.asPath().relativize(newDir).normalize().toString();

        try (var conn = dataSource.getConnection();
             var insert = conn.prepareStatement("""
                INSERT INTO FILE_STORAGE(PATH, TYPE, DESCRIPTION, BASE_ID)
                VALUES (?, ?, ?, ?)
                """);
             var query = conn.prepareStatement("""
                SELECT ID FROM FILE_STORAGE WHERE PATH = ? AND BASE_ID = ?
                """)
             ) {
            insert.setString(1, relDir);
            insert.setString(2, type.name());
            insert.setString(3, description);
            insert.setLong(4, base.id().id());

            if (insert.executeUpdate() < 1) {
                throw new SQLException("Failed to insert storage");
            }


            query.setString(1, relDir);
            query.setLong(2, base.id().id());
            var rs = query.executeQuery();

            if (rs.next()) {
                var storage = getStorage(new FileStorageId(rs.getLong("ID")));

                // Write a manifest file so we can pick this up later without needing to insert it into DB
                // (e.g. when loading from outside the system)
                var manifest = new FileStorageManifest(type, description);
                manifest.write(storage);

                return storage;
            }

        }

        throw new SQLException("Failed to insert storage");
    }


    /** Allocate permanent storage in base */
    public FileStorage allocatePermanentStorage(FileStorageBase base,
                                                String relativePath,
                                                FileStorageType type,
                                                String description) throws IOException, SQLException
    {

        Path newDir = base.asPath().resolve(relativePath);

        if (Files.exists(newDir)) {
            throw new IllegalArgumentException("Storage already exists: " + newDir);
        }

        Files.createDirectory(newDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));

        try (var conn = dataSource.getConnection();
             var update = conn.prepareStatement("""
                INSERT INTO FILE_STORAGE(PATH, TYPE, DESCRIPTION, BASE_ID)
                VALUES (?, ?, ?, ?)
                """);
             var query = conn.prepareStatement("""
                SELECT ID
                FROM FILE_STORAGE WHERE PATH = ? AND BASE_ID = ?
                """)
        ) {
            update.setString(1, relativePath);
            update.setString(2, type.name());
            update.setString(3, description);
            update.setLong(4, base.id().id());

            if (update.executeUpdate() < 1)
                throw new SQLException("Failed to insert storage");

            query.setString(1, relativePath);
            query.setLong(2, base.id().id());
            var rs = query.executeQuery();

            if (rs.next()) {
                return new FileStorage(
                        new FileStorageId(rs.getLong("ID")),
                        base,
                        type,
                        LocalDateTime.now(),
                        newDir.toString(),
                        "",
                        description
                );
            }

        }

        throw new SQLException("Failed to insert storage");
    }

    public FileStorage getStorageByType(FileStorageType type) throws SQLException {
        String override = System.getProperty(type.overrideName());

        if (override != null) {
            // It is sometimes desirable to be able to override the
            // configured location of a FileStorage when running a process
            //

            if (!Files.isDirectory(Path.of(override))) {
                throw new IllegalStateException("FileStorageType " + type.name() + " was overridden, but location '" + override + "' does not exist!");
            }

            return FileStorage.createOverrideStorage(type, FileStorageBaseType.CURRENT, override);
        }

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                     SELECT PATH, STATE, DESCRIPTION, ID, BASE_ID, CREATE_DATE
                     FROM FILE_STORAGE_VIEW WHERE TYPE = ? AND NODE = ?
                     """)) {
            stmt.setString(1, type.name());
            stmt.setInt(2, node);

            long storageId;
            long baseId;
            String path;
            String state;
            String description;
            LocalDateTime createDateTime;

            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    baseId = rs.getLong("BASE_ID");
                    storageId = rs.getLong("ID");
                    createDateTime = rs.getTimestamp("CREATE_DATE").toLocalDateTime();
                    path = rs.getString("PATH");
                    state = rs.getString("STATE");
                    description = rs.getString("DESCRIPTION");
                }
                else {
                    return null;
                }

                var base = getStorageBase(new FileStorageBaseId(baseId));

                return new FileStorage(
                        new FileStorageId(storageId),
                        base,
                        type,
                        createDateTime,
                        path,
                        state,
                        description
                );
            }
        }
    }

    public List<FileStorage> getStorage(List<FileStorageId> ids) throws SQLException {
        List<FileStorage> ret = new ArrayList<>();
        for (var id : ids) {
            ret.add(getStorage(id));
        }
        return ret;
    }

    /** @return the storage with the given id, or null if it does not exist */
    public FileStorage getStorage(FileStorageId id) throws SQLException {

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                     SELECT PATH, TYPE, STATE, DESCRIPTION, CREATE_DATE, ID, BASE_ID
                     FROM FILE_STORAGE_VIEW WHERE ID = ?
                     """)) {
            stmt.setLong(1, id.id());

            long storageId;
            long baseId;
            String path;
            String state;
            String description;
            FileStorageType type;
            LocalDateTime createDateTime;

            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    baseId = rs.getLong("BASE_ID");
                    storageId = rs.getLong("ID");
                    type = FileStorageType.valueOf(rs.getString("TYPE"));
                    path = rs.getString("PATH");
                    state = rs.getString("STATE");
                    description = rs.getString("DESCRIPTION");
                    createDateTime = rs.getTimestamp("CREATE_DATE").toLocalDateTime();
                }
                else {
                    return null;
                }

                var base = getStorageBase(new FileStorageBaseId(baseId));

                return new FileStorage(
                        new FileStorageId(storageId),
                        base,
                        type,
                        createDateTime,
                        path,
                        state,
                        description
                );
            }
        }
    }

    public void removeFileStorage(FileStorageId id) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                     DELETE FROM FILE_STORAGE WHERE ID = ?
                     """)) {
            stmt.setLong(1, id.id());
            stmt.executeUpdate();
        }
    }

    public List<FileStorage> getEachFileStorage() {
        List<FileStorage> ret = new ArrayList<>();
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                     SELECT PATH, STATE, TYPE, DESCRIPTION, CREATE_DATE, ID, BASE_ID
                     FROM FILE_STORAGE_VIEW
                     """)) {

            long storageId;
            long baseId;
            String path;
            String state;
            String description;
            LocalDateTime createDateTime;
            FileStorageType type;

            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    baseId = rs.getLong("BASE_ID");
                    storageId = rs.getLong("ID");
                    path = rs.getString("PATH");
                    state = rs.getString("STATE");
                    type = FileStorageType.valueOf(rs.getString("TYPE"));
                    description = rs.getString("DESCRIPTION");
                    createDateTime = rs.getTimestamp("CREATE_DATE").toLocalDateTime();
                    var base = getStorageBase(new FileStorageBaseId(baseId));

                    ret.add(new FileStorage(
                            new FileStorageId(storageId),
                            base,
                            type,
                            createDateTime,
                            path,
                            state,
                            description
                    ));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return ret;
    }

    public void flagFileForDeletion(FileStorageId id) throws SQLException {
        setFileStorageState(id, "DELETE");
    }

    public void enableFileStorage(FileStorageId id) throws SQLException {
        setFileStorageState(id, "ACTIVE");
    }
    public void disableFileStorage(FileStorageId id) throws SQLException {
        setFileStorageState(id, "");
    }

    private void setFileStorageState(FileStorageId id, String state) throws SQLException {
        try (var conn = dataSource.getConnection();
             var flagStmt = conn.prepareStatement("UPDATE FILE_STORAGE SET STATE = ? WHERE ID = ?")) {
            flagStmt.setString(1, state);
            flagStmt.setLong(2, id.id());
            flagStmt.executeUpdate();
        }
    }

    public void disableFileStorageOfType(int nodeId, FileStorageType type) throws SQLException {
        try (var conn = dataSource.getConnection();
             var flagStmt = conn.prepareStatement("""
                UPDATE FILE_STORAGE
                INNER JOIN FILE_STORAGE_BASE ON BASE_ID=FILE_STORAGE_BASE.ID
                SET FILE_STORAGE.STATE = ''
                WHERE FILE_STORAGE.TYPE = ?
                AND FILE_STORAGE_BASE.NODE=?
                """)) {
            flagStmt.setString(1, type.name());
            flagStmt.setInt(2, nodeId);
            flagStmt.executeUpdate();
        }
    }

    public List<FileStorageId> getActiveFileStorages(int nodeId, FileStorageType type) throws SQLException
    {

        try (var conn = dataSource.getConnection();
             var queryStmt = conn.prepareStatement("""
                SELECT FILE_STORAGE.ID FROM FILE_STORAGE
                INNER JOIN FILE_STORAGE_BASE ON BASE_ID=FILE_STORAGE_BASE.ID
                WHERE FILE_STORAGE.TYPE = ?
                AND STATE='ACTIVE'
                AND FILE_STORAGE_BASE.NODE=?
                """)) {
            queryStmt.setString(1, type.name());
            queryStmt.setInt(2, nodeId);
            var rs = queryStmt.executeQuery();
            List<FileStorageId> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(new FileStorageId(rs.getInt(1)));
            }
            return ids;
        }
    }

}
