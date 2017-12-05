import groovy.transform.ToString

class JobConfig {
    String buildArgs
    String testArgs
    String assembler = "binutils"
    String name = null
}
env = ["JOB_NAME":"CHERI1-TEST"]
def echo(String args) {
    println(args)
}

// TODO: instead of this we could also have a Jenkinsfile per config and use JobDSL to generate one job per jenkinsfile
JobConfig getJobParameters() {
    String jobName = env.JOB_NAME
    echo "Computing job paramters for ${jobName}"
    if (jobName.endsWith('-pipeline')) {
        jobName = jobName.minus('-pipeline')
    }
    Map config = [
        "CHERI1-TEST": new JobConfig(
            buildArgs: 'CAP=True',
            testArgs: 'NOFUZZR=1 GENERIC_L1=1 STATCOUNTERS=1 ALLOW_UNALIGNED=1 SIM_TRACE_OPTS= nosetests_combined.xml'),
        "CHERI1-CACHECORE-TEST": JobConfig.newInstance(
            buildArgs: 'CAP=True ICACHECORE=1 DCACHECORE=1',
            testArgs: 'NOFUZZR=1 GENERIC_L1=1 SIM_TRACE_OPTS= nosetest_cached'),
        "CHERI1-FPU-TEST": JobConfig.newInstance(
            buildArgs: 'CAP=True COP1=1',
            testArgs: 'COP1=1 TEST_PS=1 CLANG=0 NOFUZZR=1 GENERIC_L1=1 SIM_TRACE_OPTS= nosetests_combined.xml'),
        "CHERI1-CAP128-TEST": JobConfig.newInstance(
            buildArgs: 'CAP128=True',
            testArgs: 'GENERIC_L1=1 CAP_SIZE=128 PERM_SIZE=19 NOFUZZR=1 SIM_TRACE_OPTS= nosetest_cached'),
        "CHERI1-MICRO-TEST": JobConfig.newInstance(
            buildArgs: 'MICRO=True CAP=True NOWATCH=True',
            testArgs: 'CHERI_MICRO=1 NOFUZZR=1 SIM_TRACE_OPTS= nosetest_cached'),
        "CHERI1-MULTI1-TEST": JobConfig.newInstance(
            buildArgs: 'MULTI=1 CAP=True',
            testArgs: 'NOFUZZR=1 GENERIC_L1=1 SIM_TRACE_OPTS= nosetests_cached.xml'),
        "CHERI1-MULTI2-TEST": JobConfig.newInstance(
            buildArgs: 'MULTI=2 CAP=True',
            testArgs: 'GENERIC_L1=1 MULTI=1 NOFUZZR=1 SIM_TRACE_OPTS= nosetests_cachedmulti.xml'),
    ]
    JobConfig result = config.get(jobName)
    if (!result) {
        error("No configuration found for job ${jobName}! Please add one to the Map above")
    } else {
        echo("FOUND JOB CONFIG")
    }
    return result
}
println(getJobParameters().properties)