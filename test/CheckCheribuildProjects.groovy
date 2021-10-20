import com.lesfurets.jenkins.unit.BaseRegressionTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestName

class CheckCheribuildProjects extends BaseRegressionTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder()

    @Rule
    public final TestName testName = new TestName();

    @Override
    @Before
    void setUp() throws Exception {
        scriptRoots += 'vars'
        super.setUp()
        CommonTestHelper.setupTestEnv(folder, this, testName.getMethodName())
    }

    @Test
    void qemu_test() throws Exception {
//        def script = loadScript("test-scripts/qemu.groovy")
//        script.run()
//        printCallStack()
        CommonTestHelper.addEnvVars(this, ["JOB_NAME": "QEMU/qemu-cheri"])
        def script = runScript("test-scripts/qemu.groovy")
        // script.run()
        printCallStack()
        assertJobStatusUnstable()
    }

    @Test
    void llvm_test() throws Exception {
        CommonTestHelper.addEnvVars(this, [JOB_NAME   : "CLANG-LLVM-linux/dev",
                                           BRANCH_NAME: "dev",
                                           WORKSPACE  : "/workspace",])
        def script = runScript("test-scripts/llvm.groovy")
        // script.run()
        printCallStack()
        assertJobStatusUnstable()
    }

    @Test
    void postgres_test() throws Exception {
        CommonTestHelper.addEnvVars(this, [JOB_NAME       : "postgres-with-asserts/96-cheri",
                                           BRANCH_NAME    : "96-cheri",
                                           WORKSPACE      : "/workspace",
                                           EXECUTOR_NUMBER: "8",])
        def script = runScript("test-scripts/postgres.groovy")
        // script.run()
        printCallStack()
        assertJobStatusSuccess()
    }

    @Test
    void newlib_test() throws Exception {
        CommonTestHelper.addEnvVars(this, [JOB_NAME       : "newlib-baremetal/master",
                                           BRANCH_NAME    : "master",
                                           WORKSPACE      : "/workspace",
                                           EXECUTOR_NUMBER: "8",])
        def script = runScript("test-scripts/newlib-baremetal.groovy")
        // script.run()
        printCallStack()
        assertJobStatusSuccess()
    }

    @Test
    void cheribsd_test() throws Exception {
        CommonTestHelper.addEnvVars(this, [JOB_NAME       : "cheribsd/master",
                                           BRANCH_NAME    : "master",
                                           WORKSPACE      : "/workspace",
                                           EXECUTOR_NUMBER: "8",])
        def script = runScript("test-scripts/cheribsd.groovy")
        // script.run()
        printCallStack()
        assertJobStatusUnstable()
    }

    @Test
    void cheribsd_dev_test() throws Exception {
        CommonTestHelper.addEnvVars(this, [JOB_NAME       : "cheribsd/dev",
                                           BRANCH_NAME    : "dev",
                                           WORKSPACE      : "/workspace",
                                           EXECUTOR_NUMBER: "8",])
        def script = runScript("test-scripts/cheribsd.groovy")
        printCallStack()
        assertJobStatusUnstable()
    }

    @Test
    void cheribsd_testsuite_test() throws Exception {
        CommonTestHelper.addEnvVars(this, [JOB_NAME       : "CheriBSD-testsuite/dev",
                                           BRANCH_NAME    : "dev",
                                           WORKSPACE      : "/workspace",
                                           EXECUTOR_NUMBER: "8",])
        def script = runScript("test-scripts/cheribsd.groovy")
        printCallStack()
        assertJobStatusUnstable()
    }

    @Test
    void cheribsd_upstream_llvm_merge_test() throws Exception {
        CommonTestHelper.addEnvVars(this, [JOB_NAME       : "cheribsd/upstream-llvm-merge",
                                           BRANCH_NAME    : "upstream-llvm-merge",
                                           WORKSPACE      : "/workspace",
                                           EXECUTOR_NUMBER: "8",])
        def script = runScript("test-scripts/cheribsd.groovy")
        // script.run()
        printCallStack()
        assertJobStatusUnstable()
    }

    @Test
    void cerberus_test() throws Exception {
        CommonTestHelper.addEnvVars(this, [JOB_NAME       : "cerberus-CHERI",
                                           WORKSPACE      : "/workspace",
                                           EXECUTOR_NUMBER: "8",])
        def script = runScript("test-scripts/cerberus-cheri.groovy")
        // script.run()
        printCallStack()
        assertJobStatusSuccess()
    }

    @Test
    void gdb_test() throws Exception {
        CommonTestHelper.addEnvVars(this, [JOB_NAME   : "GDB/mips-cheri_8.3",
                                           BRANCH_NAME: "mips-cheri_8.3",
                                           WORKSPACE  : "/workspace",])
        def script = runScript("test-scripts/gdb.groovy")
        // script.run()
        printCallStack()
        assertJobStatusSuccess()
    }

    @Test
    void mibench_new_test() throws Exception {
        CommonTestHelper.addEnvVars(this, [JOB_NAME   : "Mibench-pipeline",
                                           WORKSPACE  : "/workspace",])
        def script = runScript("test-scripts/mibench-new.groovy")
        // script.run()
        printCallStack()
        assertJobStatusSuccess()
    }

    @Test
    void qtbase_test() throws Exception {
        CommonTestHelper.addEnvVars(this, [JOB_NAME   : "QtBase-pipeline",
                                           BRANCH_NAME: "5.15",
                                           WORKSPACE  : "/workspace",])
        def script = runScript("jobs/qtbase.groovy")
        // script.run()
        printCallStack()
        assertJobStatusUnstable()
    }

    @Test
    void cmake_test() throws Exception {
        CommonTestHelper.addEnvVars(this, [JOB_NAME   : "CMake",
                                           BRANCH_NAME: "release",
                                           WORKSPACE  : "/workspace",])
        def script = runScript("jobs/cmake.groovy")
        // script.run()
        printCallStack()
        assertJobStatusSuccess()
    }
}
