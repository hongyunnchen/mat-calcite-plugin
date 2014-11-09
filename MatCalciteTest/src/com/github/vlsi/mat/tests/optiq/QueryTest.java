package com.github.vlsi.mat.tests.optiq;

import com.github.vlsi.mat.optiq.OptiqDataSource;
import com.google.common.base.Joiner;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.util.VoidProgressListener;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class QueryTest {
    static ISnapshot snapshot;

    @BeforeClass
    public static void openSnapshot() throws SnapshotException {
        File file = new File("dumps", "mvn1m_jdk18.hprof");
        System.out.println("exists = " + file.exists() + ", file = " + file.getAbsolutePath());
        snapshot = SnapshotFactory.openSnapshot(file, new VoidProgressListener());
    }

    @AfterClass
    public static void closeSnapshot() {
        SnapshotFactory.dispose(snapshot);
        snapshot = null;
    }

    @Test
    public void countStrings() throws SQLException {
        returnsInOrder("select count(*) CNT from \"java.lang.String\"",
                new String[]{"CNT", "3256"});
    }

    @Test
    public void joinOptimization() throws SQLException {
        // Unfortunately, this is not yet optimized to snapshot.getObject(get_id(u.path))
        returnsInOrder("explain plan for select u.\"@ID\", s.\"@RETAINED\" from \"java.lang.String\" s join \"java.net.URL\" u on (s.\"@ID\" = get_id(u.path))",
                new String[]{"PLAN", "EnumerableCalcRel(expr#0..3=[{inputs}], @ID=[$t0], @RETAINED=[$t3])\n"
                        + "  EnumerableJoinRel(condition=[=($1, $2)], joinType=[inner])\n"
                        + "    EnumerableCalcRel(expr#0=[{inputs}], expr#1=[0], expr#2=[GET_SNAPSHOT($t1)], expr#3=[GET_IOBJECT($t2, $t0)], expr#4=['path'], expr#5=[RESOLVE_REFERENCE($t3, $t4)], expr#6=[get_id($t5)], @ID=[$t0], $f15=[$t6])\n"
                        + "      EnumerableTableAccessRel(table=[[HEAP, $ids$:java.net.URL]])\n"
                        + "    EnumerableCalcRel(expr#0=[{inputs}], expr#1=[0], expr#2=[GET_SNAPSHOT($t1)], expr#3=[GET_RETAINED_SIZE($t2, $t0)], @ID=[$t0], @RETAINED=[$t3])\n"
                        + "      EnumerableTableAccessRel(table=[[HEAP, $ids$:java.lang.String]])\n"});
    }

    @Test
    public void selectAllColumns() throws SQLException {
        execute("select * from \"java.util.HashMap\"", 2);
    }

    @Test
    public void selectIDColumn() throws SQLException {
        execute("select \"@ID\" from \"java.util.HashMap\"", 2);
    }

    @Test
    public void selectShallowColumn() throws SQLException {
        execute("select \"@SHALLOW\" from \"java.util.HashMap\"", 2);
    }

    @Test
    public void testReadme() throws SQLException {
        execute("explain plan for select toString(file) file, count(*) cnt, sum(\"@RETAINED\") sum_retained, sum(\"@SHALLOW\") sum_shallow\n"
                + "  from \"java.net.URL\"\n"
                + " group by toString(file)\n"
                + "having count(*)>1\n"
                + " order by sum(\"@RETAINED\") desc", 5);

        execute("select toString(file) file, count(*) cnt, sum(\"@RETAINED\") sum_retained, sum(\"@SHALLOW\") sum_shallow\n"
                + "  from \"java.net.URL\"\n"
                + " group by toString(file)\n"
                + "having count(*)>1\n"
                + " order by sum(\"@RETAINED\") desc", 5);
    }

    private void returnsInOrder(String sql, Object[] expected) throws SQLException {
        Object[] actuals = executeToCSV(sql).toArray();
        System.out.println("Arrays.toString(expected) = " + Arrays.toString(expected));
        System.out.println("Arrays.toString(actuals) = " + Arrays.toString(actuals));
        Assert.assertArrayEquals(sql, expected, actuals);
    }

    private List<String> executeToCSV(String sql) throws SQLException {
        List<String> res = new ArrayList<String>();
        System.out.println("sql = " + sql);
        Connection con = OptiqDataSource.getConnection(snapshot);
        PreparedStatement ps = con.prepareStatement(sql);
        ResultSet rs = ps.executeQuery();
        ResultSetMetaData md = rs.getMetaData();
        Joiner joiner = Joiner.on('|');
        List<String> row = new ArrayList<String>();
        final int columnCount = md.getColumnCount();
        for (int i = 1; i <= columnCount; i++) {
            row.add(md.getColumnName(i));
        }
        res.add(joiner.join(row));
        while(rs.next()) {
            row.clear();
            for (int i = 1; i <= columnCount; i++) {
                row.add(String.valueOf(rs.getObject(i)));
            }
            res.add(joiner.join(row));
        }
        return res;
    }

    private void execute(String sql, int limit) throws SQLException {
        System.out.println("sql = " + sql);
        Connection con = OptiqDataSource.getConnection(snapshot);
        PreparedStatement ps = con.prepareStatement(sql);
        ResultSet rs = ps.executeQuery();
        ResultSetMetaData md = rs.getMetaData();
        for (int j = 0; rs.next() && j < limit; j++) {
            for (int i = 1; i <= md.getColumnCount(); i++) {
                System.out.println(md.getColumnName(i) + ": " + String.valueOf(rs.getObject(i)));
            }
            System.out.println();
        }
        OptiqDataSource.close(rs, ps, con);
    }
}
