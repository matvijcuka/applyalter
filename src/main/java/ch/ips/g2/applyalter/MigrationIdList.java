package ch.ips.g2.applyalter;

import com.thoughtworks.xstream.annotations.XStreamAlias;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

import static ch.ips.g2.applyalter.ReportLevel.DETAIL;
import static ch.ips.g2.applyalter.ReportLevel.STATEMENT_STEP;

/**
 * General migration: used in cases where full range splitting would be too ineffective.
 * It also supports ids that are not BIGINT (varchar, etc) and composite keys. With some effort,
 * it can be used even in very complicated case when the "id list" is used for general comparison.
 * <p/>
 * Principle: special query is used to obtain list of IDs. Result of this query is stored to
 * temporary table and then processed in batches. Every batch is made by deleting rows from the idlist
 * temporary table and inserting into second temporary table. This temporary table is then used in the
 * main query.
 *
 * @author Kamil Podlesak &lt;kamil.podlesak@ips-ag.cz&gt;
 * @version $Id$
 */
@XStreamAlias("migration-id-list")
public class MigrationIdList extends AbstractMigration {
    public static final String DEFAULT_PLACEHOLDER = "ID_LIST";


    private String idquery;
    private String idcolumn;

    /**
     * Source statement: required, must contain SELECT statement that produces temporary table.
     */
    public String getIdquery() {
        return idquery;
    }

    /**
     * Source statement: required, must contain SELECT statement that produces temporary table.
     */
    public void setIdquery(String idquery) {
        this.idquery = idquery;
    }

    /**
     * Name of the PK column.
     * Note: it might be several names delimited by commas.
     */
    public String getIdcolumn() {
        return idcolumn;
    }

    /**
     * Name of the PK column.
     * Note: it might be several names delimited by commas.
     */
    public void setIdcolumn(String idcolumn) {
        this.idcolumn = idcolumn;
    }

    @Override
    protected String getDefaultPlaceholder() {
        return DEFAULT_PLACEHOLDER;
    }

    //-----------------------------------------------------------------------------------------------------------------

    /**
     * Check validity of this statement; raise exception if the alterscript is invalid.
     *
     * @throws ApplyAlterException invalid statement, invalid alterscript
     */
    public void checkParameters() {
        if (statement == null)
            throw new ApplyAlterException("invalid apply script: missing \"statement\" for " + this);
        if (idquery == null)
            throw new ApplyAlterException("invalid apply script: missing \"idquery\"" + this);
        if (idcolumn == null)
            throw new ApplyAlterException("invalid apply script: missing \"idcolumn\"" + this);
        if (step == null || step.longValue() < 1)
            throw new ApplyAlterException("invalid apply script: missing or invalid \"step\" for " + this);
    }

    //-----------------------------------------------------------------------------------------------------------------

    public static final String TEMP_TABLE_MAIN = "MGR_IDS";
    public static final String TEMP_TABLE_BATCH = "MIG_BATCH";

    /**
     * Create temporary table. This method is DB2-specific!
     *
     * @param dbConn database connection and other data
     * @param ctx    run context, used for logging
     * @return name of the created table
     * @throws ApplyAlterException attempt to create temporary table has failed
     */
    protected String createTempTable(DbInstance dbConn, RunContext ctx, String tableBaseName)
            throws ApplyAlterException {
        if (getIdquery() == null)
            throw new ApplyAlterException("invalid apply script: missing source query for " + this);

        String tableName = dbConn.makeTemporaryTableName(tableBaseName);

        ctx.report(STATEMENT_STEP, "creating temporary table %s", tableName);

        try {
            final Connection connection = dbConn.getConnection(ctx);

            //step 1: create temporary table with the columns of query
            String sql = dbConn.makeCreateTemporaryTableAsSql(tableName, getIdquery().trim());
            ctx.report(DETAIL, "creating temporary table by query: %s", sql);
            DbUtils.executeUpdate(connection, sql);

            //important: create index
            String indexSql = String.format("create index %1$s_IDX on %1$s (%2$s)", tableName, getIdcolumn());
            ctx.report(DETAIL, "  creating index: %s", indexSql);
            DbUtils.executeUpdate(connection, indexSql);

        } catch (SQLException e) {
            throw new ApplyAlterException("failed to create temporary table %s: %s", e, tableName, e.getMessage());
        }
        return tableName;
    }

    //-----------------------------------------------------------------------------------------------------------------

    public void execute(DbInstance dbConn, RunContext ctx, Map<String, byte[]> datafiles)
            throws ApplyAlterException, SQLException {
        checkParameters();

        //ensure that everything is committed before migration
        final Connection connection = dbConn.getConnection(ctx);
        commitStep(ctx, connection);

        //create temporary table, empty
        final String tableMain = createTempTable(dbConn, ctx, TEMP_TABLE_MAIN);
        final String tableBatch = createTempTable(dbConn, ctx, TEMP_TABLE_BATCH);
        //always commit temporary tables
        connection.commit();

        //prepare main query right now, for the case the alterscript is invalid
        ProcessedQuery mainQuery = processQuery(getStatement(),
                String.format("(select * from %s)", tableBatch)
        );
        if (mainQuery.replacements < 1) {
            throw new ApplyAlterException("invalid alter script: no %s in the query; %s",
                    getPlaceholder() == null ? getDefaultPlaceholder() : getPlaceholder(), this.toString());
        }


        //fill the table
        int totalIdCount = fillIdList(ctx, connection, tableMain);

        ctx.report(ReportLevel.STATEMENT, "total %d rows to be migrated", totalIdCount);

        //----------------- the main migration part ---------------
        PreparedStatement stCopyBatch = null;
        PreparedStatement stDeleteBatch = null;
        PreparedStatement stCleanBatchTable = null;
        PreparedStatement stMain = null;
        try {
            //prepare statements for batches
            String sqlCopyBatch = String.format("insert into %2$s (select * from %1$s fetch first %3$d rows only)",
                    tableMain, tableBatch, getStep());
            String sqlDeleteBatch = String.format("delete from %1$s where (%3$s) in (select %3$s from %2$s)",
                    tableMain, tableBatch, getIdcolumn());

            ctx.report(STATEMENT_STEP, "migration query 1: %s", sqlCopyBatch);
            ctx.report(STATEMENT_STEP, "migration query 2: %s", mainQuery.statement);
            ctx.report(STATEMENT_STEP, "migration query 3: %s", sqlDeleteBatch);

            stCopyBatch = connection.prepareStatement(sqlCopyBatch);
            stDeleteBatch = connection.prepareStatement(sqlDeleteBatch);
            stCleanBatchTable = connection.prepareStatement(String.format("delete from %s", tableBatch));
            stMain = connection.prepareStatement(mainQuery.statement);

            final int supposedBatchCount = totalIdCount / getStep().intValue();
            //and process the batches
            int batchCount = 0;
            int updatedCount = 0;
            int processedCount = 0;
            do {
                //batch, step 1: select the batch
                final int copied = stCopyBatch.executeUpdate();
                if (copied < 1) {
                    //end
                    break;
                }
                batchCount++;

                //ctx.report( DETAIL, "  batch %d: %d rows", batchCount, copied );
                final int updated = stMain.executeUpdate();
                updatedCount += updated;
                processedCount += copied;

                ctx.report(DETAIL, "  batch %d/%d: %d of %d updated",
                        batchCount, supposedBatchCount, updated, copied);

                //and delete batch
                stDeleteBatch.executeUpdate();
                stCleanBatchTable.executeUpdate();

                //the most important thing: commit
                commitStep(ctx, connection);

                //ctx.report( DETAIL, "   batch cleaned up, going to next one" );
            }
            while (true);

            ctx.report(STATEMENT_STEP, " migration finished, total %d rows updated in %d batches (%d rows processed)",
                    updatedCount, batchCount, processedCount
            );

        } finally {
            DbUtils.close(stCopyBatch);
            DbUtils.close(stDeleteBatch);
            DbUtils.close(stCleanBatchTable);
            DbUtils.close(stMain);
        }

    }

    /**
     * Phase A: fill the main temporary table.
     */
    private int fillIdList(RunContext ctx, Connection connection, String tableMain) {
        try {
            String insertSql = String.format("INSERT INTO %s (%s)", tableMain, getIdquery());
            ctx.report(STATEMENT_STEP, "getting source data: %s", insertSql);
            return DbUtils.executeUpdate(connection, insertSql);
        } catch (SQLException e) {
            throw new ApplyAlterException("failed to get main ID list %s: %s", e, tableMain, e.getMessage());
        }
    }

    //-----------------------------------------------------------------------------------------------------------------
    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append(this.getClass().getSimpleName()).append(": ");
        b.append("logid: ").append(logid).append("\n");
        b.append("statement:").append(" ").append(statement).append("\n");
        b.append("idquery: ").append(idquery).append("\n");
        b.append("step: ").append(step).append("\n");
        b.append("description: ").append(description).append("\n");
        return b.toString();
    }

}
