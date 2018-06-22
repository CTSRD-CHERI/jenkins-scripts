import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

import static com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration.library
import static com.lesfurets.jenkins.unit.global.lib.LocalSource.localSource

import org.junit.Before
import org.junit.Test

import com.lesfurets.jenkins.unit.cps.BasePipelineTestCPS

class TestCHERI1Test extends BasePipelineTest {

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
    void cheri1_fpu_test() throws Exception {
        binding.setVariable("env", ["JOB_NAME":"CHERI1-FPU-TEST-pipeline/master"])
        def script = loadScript("test-scripts/CHERI1Test.groovy")
        script.run()
        printCallStack()
    }

    @Test
    void sail2_test() throws Exception {
        binding.setVariable("env", ["JOB_NAME":"sail2-CHERI256-pipeline/master"])
        def script = loadScript("test-scripts/sail2.groovy")
        script.run()
        printCallStack()
    }

    @Test
    void qemu_test() throws Exception {
        binding.setVariable("env", ["JOB_NAME":"QEMU-CHERI256-TEST-pipeline/master"])
        def script = loadScript("test-scripts/qemu_test.groovy")
        script.run()
        printCallStack()
    }

    @Test
    void ber1_test() throws Exception {
        binding.setVariable("env", ["JOB_NAME":"BERI1-TEST-pipeline/master"])
        def script = loadScript("test-scripts/CHERI1Test.groovy")
        script.run()
        printCallStack()
    }
}