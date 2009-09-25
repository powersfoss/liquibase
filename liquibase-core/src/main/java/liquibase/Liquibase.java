package liquibase;

import liquibase.changelog.ChangeLogIterator;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.changelog.filter.*;
import liquibase.changelog.visitor.*;
import liquibase.database.Database;
import liquibase.database.DatabaseConnection;
import liquibase.database.DatabaseFactory;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.exception.LockException;
import liquibase.executor.Executor;
import liquibase.executor.ExecutorService;
import liquibase.executor.LoggingExecutor;
import liquibase.lockservice.DatabaseChangeLogLock;
import liquibase.lockservice.LockService;
import liquibase.logging.LogFactory;
import liquibase.logging.Logger;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ResourceAccessor;
import liquibase.sql.visitor.SqlVisitor;
import liquibase.statement.core.UpdateStatement;
import liquibase.util.LiquibaseUtil;
import liquibase.util.StreamUtil;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.text.DateFormat;
import java.util.*;

/**
 * Core LiquiBase facade.
 * Although there are several ways of executing LiquiBase (Ant, command line, etc.) they are all wrappers around this class.
 */
public class Liquibase {

    public static final String SHOULD_RUN_SYSTEM_PROPERTY = "liquibase.should.run";

    private String changeLogFile;
    private ResourceAccessor resourceAccessor;

    protected Database database;
    private Logger log;

    private Map<String, Object> changeLogParameters = new HashMap<String, Object>();

    public Liquibase(String changeLogFile, ResourceAccessor resourceAccessor, DatabaseConnection conn) throws DatabaseException {
        this(changeLogFile, resourceAccessor, DatabaseFactory.getInstance().findCorrectDatabaseImplementation(conn));
    }

    public Liquibase(String changeLogFile, ResourceAccessor resourceAccessor, Database database) {
        log = LogFactory.getLogger();

        if (changeLogFile != null) {
            this.changeLogFile = changeLogFile.replace('\\', '/');  //convert to standard / if usign absolute path on windows
        }
        this.resourceAccessor = resourceAccessor;

        this.database = database;
    }

    public Database getDatabase() {
        return database;
    }

    /**
     * FileOpener to use for accessing changelog files.
     */
    public ResourceAccessor getFileOpener() {
        return resourceAccessor;
    }

    /**
     * Use this function to override the current date/time function used to insert dates into the database.
     * Especially useful when using an unsupported database.
     */
    public void setCurrentDateTimeFunction(String currentDateTimeFunction) {
        if (currentDateTimeFunction != null) {
            this.database.setCurrentDateTimeFunction(currentDateTimeFunction);
        }
    }

    public Object getChangeLogParameterValue(String paramter) {
        return changeLogParameters.get(paramter);
    }

    public void setChangeLogParameterValue(String paramter, Object value) {
        if (!changeLogParameters.containsKey(paramter)) {
            changeLogParameters.put(paramter, value);
        }
    }

    public void update(String contexts) throws LiquibaseException {

        LockService lockService = LockService.getInstance(database);
        lockService.waitForLock();

        try {
            database.checkDatabaseChangeLogTable();

            DatabaseChangeLog changeLog = ChangeLogParserFactory.getInstance().getParser(changeLogFile).parse(changeLogFile, changeLogParameters, resourceAccessor);
            changeLog.validate(database);
            ChangeLogIterator changeLogIterator = new ChangeLogIterator(changeLog,
                    new ShouldRunChangeSetFilter(database),
                    new ContextChangeSetFilter(contexts),
                    new DbmsChangeSetFilter(database));

            changeLogIterator.run(new UpdateVisitor(database), database);
        } finally {
            try {
                lockService.releaseLock();
            } catch (LockException e) {
                log.severe("Could not release lock", e);
            }
        }
    }

    public void update(String contexts, Writer output) throws LiquibaseException {
        Executor oldTemplate = ExecutorService.getInstance().getExecutor(database);
        LoggingExecutor loggingExecutor = new LoggingExecutor(ExecutorService.getInstance().getExecutor(database), output, database);
        ExecutorService.getInstance().setExecutor(database, loggingExecutor);

        outputHeader("Update Database Script");

        LockService lockService = LockService.getInstance(database);
        lockService.waitForLock();

        try {

            update(contexts);

            output.flush();
        } catch (IOException e) {
            throw new LiquibaseException(e);
        } finally {
            lockService.releaseLock();
        }

        ExecutorService.getInstance().setExecutor(database, oldTemplate);
    }

    public void update(int changesToApply, String contexts) throws LiquibaseException {

        LockService lockService = LockService.getInstance(database);
        lockService.waitForLock();

        try {
            database.checkDatabaseChangeLogTable();

            DatabaseChangeLog changeLog = ChangeLogParserFactory.getInstance().getParser(changeLogFile).parse(changeLogFile, changeLogParameters, resourceAccessor);
            changeLog.validate(database);

            ChangeLogIterator logIterator = new ChangeLogIterator(changeLog,
                    new ShouldRunChangeSetFilter(database),
                    new ContextChangeSetFilter(contexts),
                    new DbmsChangeSetFilter(database),
                    new CountChangeSetFilter(changesToApply));

            logIterator.run(new UpdateVisitor(database), database);
        } finally {
            lockService.releaseLock();
        }
    }

    public void update(int changesToApply, String contexts, Writer output) throws LiquibaseException {
        Executor oldTemplate = ExecutorService.getInstance().getExecutor(database);
        LoggingExecutor loggingExecutor = new LoggingExecutor(ExecutorService.getInstance().getExecutor(database), output, database);
        ExecutorService.getInstance().setExecutor(database, loggingExecutor);

        outputHeader("Update " + changesToApply + " Change Sets Database Script");

        update(changesToApply, contexts);

        try {
            output.flush();
        } catch (IOException e) {
            throw new LiquibaseException(e);
        }

        ExecutorService.getInstance().setExecutor(database, oldTemplate);
    }

    private void outputHeader(String message) throws DatabaseException {
        Executor executor = ExecutorService.getInstance().getExecutor(database);
        executor.comment("*********************************************************************");
        executor.comment(message);
        executor.comment("*********************************************************************");
        executor.comment("Change Log: " + changeLogFile);
        executor.comment("Ran at: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date()));
        executor.comment("Against: " + getDatabase().getConnection().getConnectionUserName() + "@" + getDatabase().getConnection().getURL());
        executor.comment("LiquiBase version: " + LiquibaseUtil.getBuildVersion());
        executor.comment("*********************************************************************" + StreamUtil.getLineSeparator());
    }

    public void rollback(int changesToRollback, String contexts, Writer output) throws LiquibaseException {
        Executor oldTemplate = ExecutorService.getInstance().getExecutor(database);
        ExecutorService.getInstance().setExecutor(database, new LoggingExecutor(ExecutorService.getInstance().getExecutor(database), output, database));

        outputHeader("Rollback " + changesToRollback + " Change(s) Script");

        rollback(changesToRollback, contexts);

        try {
            output.flush();
        } catch (IOException e) {
            throw new LiquibaseException(e);
        }
        ExecutorService.getInstance().setExecutor(database, oldTemplate);
    }

    public void rollback(int changesToRollback, String contexts) throws LiquibaseException {
        LockService lockService = LockService.getInstance(database);
        lockService.waitForLock();

        try {
            database.checkDatabaseChangeLogTable();

            DatabaseChangeLog changeLog = ChangeLogParserFactory.getInstance().getParser(changeLogFile).parse(changeLogFile, changeLogParameters, resourceAccessor);
            changeLog.validate(database);

            ChangeLogIterator logIterator = new ChangeLogIterator(changeLog,
                    new AlreadyRanChangeSetFilter(database.getRanChangeSetList()),
                    new ContextChangeSetFilter(contexts),
                    new DbmsChangeSetFilter(database),
                    new CountChangeSetFilter(changesToRollback));

            logIterator.run(new RollbackVisitor(database), database);
        } finally {
            try {
                lockService.releaseLock();
            } catch (LockException e) {
                log.severe("Error releasing lock", e);
            }
        }
    }

    public void rollback(String tagToRollBackTo, String contexts, Writer output) throws LiquibaseException {
        Executor oldTemplate = ExecutorService.getInstance().getExecutor(database);
        ExecutorService.getInstance().setExecutor(database, new LoggingExecutor(ExecutorService.getInstance().getExecutor(database), output, database));

        outputHeader("Rollback to '" + tagToRollBackTo + "' Script");

        rollback(tagToRollBackTo, contexts);

        try {
            output.flush();
        } catch (IOException e) {
            throw new LiquibaseException(e);
        }
        ExecutorService.getInstance().setExecutor(database, oldTemplate);
    }

    public void rollback(String tagToRollBackTo, String contexts) throws LiquibaseException {
        LockService lockService = LockService.getInstance(database);
        lockService.waitForLock();

        try {
            database.checkDatabaseChangeLogTable();

            DatabaseChangeLog changeLog = ChangeLogParserFactory.getInstance().getParser(changeLogFile).parse(changeLogFile, changeLogParameters, resourceAccessor);
            changeLog.validate(database);

            ChangeLogIterator logIterator = new ChangeLogIterator(changeLog,
                    new AfterTagChangeSetFilter(tagToRollBackTo, database.getRanChangeSetList()),
                    new ContextChangeSetFilter(contexts),
                    new DbmsChangeSetFilter(database));

            logIterator.run(new RollbackVisitor(database), database);
        } finally {
            lockService.releaseLock();
        }
    }

    public void rollback(Date dateToRollBackTo, String contexts, Writer output) throws LiquibaseException {
        Executor oldTemplate = ExecutorService.getInstance().getExecutor(database);
        ExecutorService.getInstance().setExecutor(database, new LoggingExecutor(ExecutorService.getInstance().getExecutor(database), output, database));

        outputHeader("Rollback to " + dateToRollBackTo + " Script");

        rollback(dateToRollBackTo, contexts);

        try {
            output.flush();
        } catch (IOException e) {
            throw new LiquibaseException(e);
        }
        ExecutorService.getInstance().setExecutor(database, oldTemplate);
    }

    public void rollback(Date dateToRollBackTo, String contexts) throws LiquibaseException {
        LockService lockService = LockService.getInstance(database);
        lockService.waitForLock();

        try {
            database.checkDatabaseChangeLogTable();

            DatabaseChangeLog changeLog = ChangeLogParserFactory.getInstance().getParser(changeLogFile).parse(changeLogFile, changeLogParameters, resourceAccessor);
            changeLog.validate(database);

            ChangeLogIterator logIterator = new ChangeLogIterator(changeLog,
                    new ExecutedAfterChangeSetFilter(dateToRollBackTo, database.getRanChangeSetList()),
                    new ContextChangeSetFilter(contexts),
                    new DbmsChangeSetFilter(database));

            logIterator.run(new RollbackVisitor(database), database);
        } finally {
            lockService.releaseLock();
        }
    }

    public void changeLogSync(String contexts, Writer output) throws LiquibaseException {

        LoggingExecutor outputTemplate = new LoggingExecutor(ExecutorService.getInstance().getExecutor(database), output, database);
        Executor oldTemplate = ExecutorService.getInstance().getExecutor(database);
        ExecutorService.getInstance().setExecutor(database, outputTemplate);

        outputHeader("SQL to add all changesets to database history table");

        changeLogSync(contexts);

        try {
            output.flush();
        } catch (IOException e) {
            throw new LiquibaseException(e);
        }

        ExecutorService.getInstance().setExecutor(database, oldTemplate);
    }

    public void changeLogSync(String contexts) throws LiquibaseException {
        LockService lockService = LockService.getInstance(database);
        lockService.waitForLock();

        try {
            database.checkDatabaseChangeLogTable();

            DatabaseChangeLog changeLog = ChangeLogParserFactory.getInstance().getParser(changeLogFile).parse(changeLogFile, changeLogParameters, resourceAccessor);
            changeLog.validate(database);

            ChangeLogIterator logIterator = new ChangeLogIterator(changeLog,
                    new NotRanChangeSetFilter(database.getRanChangeSetList()),
                    new ContextChangeSetFilter(contexts),
                    new DbmsChangeSetFilter(database));

            logIterator.run(new ChangeLogSyncVisitor(database), database);
        } finally {
            lockService.releaseLock();
        }
    }

    public void markNextChangeSetRan(String contexts, Writer output) throws LiquibaseException {

        LoggingExecutor outputTemplate = new LoggingExecutor(ExecutorService.getInstance().getExecutor(database), output, database);
        Executor oldTemplate = ExecutorService.getInstance().getExecutor(database);
        ExecutorService.getInstance().setExecutor(database, outputTemplate);

        outputHeader("SQL to add all changesets to database history table");

        markNextChangeSetRan(contexts);

        try {
            output.flush();
        } catch (IOException e) {
            throw new LiquibaseException(e);
        }

        ExecutorService.getInstance().setExecutor(database, oldTemplate);
    }

    public void markNextChangeSetRan(String contexts) throws LiquibaseException {
        LockService lockService = LockService.getInstance(database);
        lockService.waitForLock();

        try {
            database.checkDatabaseChangeLogTable();

            DatabaseChangeLog changeLog = ChangeLogParserFactory.getInstance().getParser(changeLogFile).parse(changeLogFile, changeLogParameters, resourceAccessor);
            changeLog.validate(database);

            ChangeLogIterator logIterator = new ChangeLogIterator(changeLog,
                    new NotRanChangeSetFilter(database.getRanChangeSetList()),
                    new ContextChangeSetFilter(contexts),
                    new DbmsChangeSetFilter(database),
                    new CountChangeSetFilter(1));

            logIterator.run(new ChangeLogSyncVisitor(database), database);
        } finally {
            lockService.releaseLock();
        }
    }

    public void futureRollbackSQL(String contexts, Writer output) throws LiquibaseException {
        LoggingExecutor outputTemplate = new LoggingExecutor(ExecutorService.getInstance().getExecutor(database), output, database);
        Executor oldTemplate = ExecutorService.getInstance().getExecutor(database);
        ExecutorService.getInstance().setExecutor(database, outputTemplate);

        outputHeader("SQL to roll back currently unexecuted changes");

        LockService lockService = LockService.getInstance(database);
        lockService.waitForLock();

        try {
            database.checkDatabaseChangeLogTable();

            DatabaseChangeLog changeLog = ChangeLogParserFactory.getInstance().getParser(changeLogFile).parse(changeLogFile, changeLogParameters, resourceAccessor);
            changeLog.validate(database);

            ChangeLogIterator logIterator = new ChangeLogIterator(changeLog,
                    new NotRanChangeSetFilter(database.getRanChangeSetList()),
                    new ContextChangeSetFilter(contexts),
                    new DbmsChangeSetFilter(database));

            logIterator.run(new RollbackVisitor(database), database);
        } finally {
            ExecutorService.getInstance().setExecutor(database, oldTemplate);
            lockService.releaseLock();
        }

        try {
            output.flush();
        } catch (IOException e) {
            throw new LiquibaseException(e);
        }

    }

    /**
     * Drops all database objects owned by the current user.
     */
    public final void dropAll() throws DatabaseException, LockException {
        dropAll(getDatabase().getDefaultSchemaName());
    }

    /**
     * Drops all database objects owned by the current user.
     */
    public final void dropAll(String... schemas) throws DatabaseException {
        try {
            LockService.getInstance(database).waitForLock();

            for (String schema : schemas) {
                log.info("Dropping Database Objects in schema: " + database.convertRequestedSchemaToSchema(schema));
                checkDatabaseChangeLogTable();
                getDatabase().dropDatabaseObjects(schema);
                checkDatabaseChangeLogTable();
                log.debug("Objects dropped successfully");
            }
        } catch (DatabaseException e) {
            throw e;
        } catch (Exception e) {
            throw new DatabaseException(e);
        } finally {
            try {
                LockService.getInstance(database).releaseLock();
            } catch (LockException e) {
                log.severe("Unable to release lock: " + e.getMessage());
            }
        }
    }

    /**
     * 'Tags' the database for future rollback
     */
    public void tag(String tagString) throws DatabaseException {
        getDatabase().tag(tagString);
    }


    public void checkDatabaseChangeLogTable() throws DatabaseException {
        getDatabase().checkDatabaseChangeLogTable();
        getDatabase().checkDatabaseChangeLogLockTable();
    }

    /**
     * Returns true if it is "save" to migrate the database.
     * Currently, "safe" is defined as running in an output-sql mode or against a database on localhost.
     * It is fine to run LiquiBase against a "non-safe" database, the method is mainly used to determine if the user
     * should be prompted before continuing.
     */
    public boolean isSafeToRunMigration() throws DatabaseException {
        return !getDatabase().isLocalDatabase();
    }

    /**
     * Display change log lock information.
     */
    public DatabaseChangeLogLock[] listLocks() throws DatabaseException, IOException, LockException {
        checkDatabaseChangeLogTable();

        return LockService.getInstance(getDatabase()).listLocks();
    }

    public void reportLocks(PrintStream out) throws LockException, IOException, DatabaseException {
        DatabaseChangeLogLock[] locks = listLocks();
        out.println("Database change log locks for " + getDatabase().getConnection().getConnectionUserName() + "@" + getDatabase().getConnection().getURL());
        if (locks.length == 0) {
            out.println(" - No locks");
        }
        for (DatabaseChangeLogLock lock : locks) {
            out.println(" - " + lock.getLockedBy() + " at " + DateFormat.getDateTimeInstance().format(lock.getLockGranted()));
        }

    }

    public void forceReleaseLocks() throws LockException, IOException, DatabaseException {
        checkDatabaseChangeLogTable();

        LockService.getInstance(getDatabase()).forceReleaseLock();
    }

    public List<ChangeSet> listUnrunChangeSets(String contexts) throws LiquibaseException {
        LockService lockService = LockService.getInstance(database);
        lockService.waitForLock();

        try {
            database.checkDatabaseChangeLogTable();

            DatabaseChangeLog changeLog = ChangeLogParserFactory.getInstance().getParser(changeLogFile).parse(changeLogFile, changeLogParameters, resourceAccessor);
            changeLog.validate(database);

            ChangeLogIterator logIterator = new ChangeLogIterator(changeLog,
                    new ShouldRunChangeSetFilter(database),
                    new ContextChangeSetFilter(contexts),
                    new DbmsChangeSetFilter(database));

            ListVisitor visitor = new ListVisitor();
            logIterator.run(visitor, database);
            return visitor.getSeenChangeSets();
        } finally {
            lockService.releaseLock();
        }
    }

    public void reportStatus(boolean verbose, String contexts, Writer out) throws LiquibaseException {
        try {
            List<ChangeSet> unrunChangeSets = listUnrunChangeSets(contexts);
            out.append(String.valueOf(unrunChangeSets.size()));
            out.append(" change sets have not been applied to ");
            out.append(getDatabase().getConnection().getConnectionUserName());
            out.append("@");
            out.append(getDatabase().getConnection().getURL());
            out.append(StreamUtil.getLineSeparator());
            if (verbose) {
                for (ChangeSet changeSet : unrunChangeSets) {
                    out.append("     ").append(changeSet.toString(false)).append(StreamUtil.getLineSeparator());
                }
            }

            out.flush();
        } catch (IOException e) {
            throw new LiquibaseException(e);
        }

    }

    /**
     * Sets checksums to null so they will be repopulated next run
     */
    public void clearCheckSums() throws LiquibaseException {
        log.info("Clearing database change log checksums");
        LockService lockService = LockService.getInstance(database);
        lockService.waitForLock();

        try {
            database.checkDatabaseChangeLogTable();

            UpdateStatement updateStatement = new UpdateStatement(getDatabase().getLiquibaseSchemaName(), getDatabase().getDatabaseChangeLogTableName());
            updateStatement.addNewColumnValue("MD5SUM", null);
            ExecutorService.getInstance().getExecutor(database).execute(updateStatement);
            getDatabase().commit();
        } finally {
            lockService.releaseLock();
        }
    }

    public void generateDocumentation(String outputDirectory) throws LiquibaseException {
        log.info("Generating Database Documentation");
        LockService lockService = LockService.getInstance(database);
        lockService.waitForLock();

        try {
            database.checkDatabaseChangeLogTable();

            DatabaseChangeLog changeLog = ChangeLogParserFactory.getInstance().getParser(changeLogFile).parse(changeLogFile, changeLogParameters, resourceAccessor);
            changeLog.validate(database);

            ChangeLogIterator logIterator = new ChangeLogIterator(changeLog,
                    new DbmsChangeSetFilter(database));

            DBDocVisitor visitor = new DBDocVisitor(database);
            logIterator.run(visitor, database);

            visitor.writeHTML(new File(outputDirectory), resourceAccessor);
        } catch (IOException e) {
            throw new LiquibaseException(e);
        } finally {
            lockService.releaseLock();
        }

//        try {
//            if (!LockService.getExecutor(database).waitForLock()) {
//                return;
//            }
//
//            DBDocChangeLogHandler changeLogHandler = new DBDocChangeLogHandler(outputDirectory, this, changeLogFile,resourceAccessor);
//            runChangeLogs(changeLogHandler);
//
//            changeLogHandler.writeHTML(this);
//        } finally {
//            releaseLock();
//        }
    }

    /**
     * Checks changelogs for bad MD5Sums and preconditions before attempting a migration
     */
    public void validate() throws LiquibaseException {

        DatabaseChangeLog changeLog = ChangeLogParserFactory.getInstance().getParser(changeLogFile).parse(changeLogFile, changeLogParameters, resourceAccessor);
        changeLog.validate(database);
    }
}