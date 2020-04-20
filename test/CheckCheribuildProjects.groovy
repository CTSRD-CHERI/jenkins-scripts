import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import static com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration.library
import static com.lesfurets.jenkins.unit.global.lib.LocalSource.localSource

class CheckCheribuildProjects extends BasePipelineTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder()

    @Override
    @Before
    void setUp() throws Exception {
        scriptRoots += 'vars'
        super.setUp()
        CommonTestHelper.setupTestEnv(folder, this)
    }

    @Test
    void qemu_test() throws Exception {
//        def script = loadScript("test-scripts/qemu.groovy")
//        script.run()
//        printCallStack()
        CommonTestHelper.addEnvVars(this, ["JOB_NAME":"QEMU/qemu-cheri"])
        def script = runScript("test-scripts/qemu.groovy")
        // script.run()
        printCallStack()
    }

	@Test
	void llvm_test() throws Exception {
		CommonTestHelper.addEnvVars(this, [
                JOB_NAME:"LLVM-linux/cap-table",
                BRANCH_NAME:"cap-table",
                WORKSPACE:"/workspace",
        ])
		def script = runScript("test-scripts/llvm.groovy")
		// script.run()
		printCallStack()
	}

    @Test
	void postgres_test() throws Exception {
		CommonTestHelper.addEnvVars(this, [
                JOB_NAME:"postgres-with-asserts/96-cheri",
                BRANCH_NAME:"96-cheri",
                WORKSPACE:"/workspace",
                EXECUTOR_NUMBER:"8",
        ])
		def script = runScript("test-scripts/postgres.groovy")
		// script.run()
		printCallStack()
	}

    @Test
    void cheribsd_test() throws Exception {
        CommonTestHelper.addEnvVars(this, [
                JOB_NAME:"cheribsd/master",
                BRANCH_NAME:"master",
                WORKSPACE:"/workspace",
                EXECUTOR_NUMBER:"8",
        ])
        def script = runScript("test-scripts/cheribsd.groovy")
        // script.run()
        printCallStack()
    }

    @Test
    void cheribsd_dev_test() throws Exception {
        CommonTestHelper.addEnvVars(this, [
                JOB_NAME:"cheribsd/master",
                JOB_NAME:"cheribsd/dev",
                BRANCH_NAME:"dev",
                WORKSPACE:"/workspace",
                EXECUTOR_NUMBER:"8",
        ])
        def script = runScript("test-scripts/cheribsd.groovy")
        // script.run()
        printCallStack()
    }


    @Test
    void cerberus_test() throws Exception {
        CommonTestHelper.addEnvVars(this, [
                JOB_NAME:"cerberus-CHERI",
                WORKSPACE:"/workspace",
                EXECUTOR_NUMBER:"8",
        ])
        def script = runScript("test-scripts/cerberus-cheri.groovy")
        // script.run()
        printCallStack()
    }
}