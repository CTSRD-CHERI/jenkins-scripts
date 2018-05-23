@Library('ctsrd-jenkins-scripts') _

properties([disableConcurrentBuilds(),
            compressBuildLog(),
            disableResume(),
            [$class: 'GithubProjectProperty', displayName: '', projectUrlStr: 'https://github.com/CTSRD-CHERI/llvm/'],
            [$class: 'CopyArtifactPermissionProperty', projectNames: '*'],
            [$class: 'JobPropertyImpl', throttle: [count: 2, durationName: 'hour', userBoost: true]],
            durabilityHint('PERFORMANCE_OPTIMIZED'),
            pipelineTriggers([githubPush()])
])

def cleanupScript = '''
# remove the 600+ useless header files
rm -rfv tarball/opt/*/include
# save some space (not sure we need all those massive binaries anyway)
# cheri-unknown-freebsd
find tarball/opt/*/bin/* -print0 | xargs -n 1 -0 $WORKSPACE/cherisdk/bin/llvm-objcopy --strip-all
$WORKSPACE/cherisdk/bin/llvm-objcopy --strip-all tarball/opt/*/*/postgresql/pgxs/src/test/regress/pg_regress
'''


for (i in ["mips" /*, "cheri128", "cheri256" */]) {
    String cpu = "${i}" // work around stupid groovy lambda captures
    cheribuildProject(target: 'postgres', cpu: cpu,
            // extraArgs: '--with-libstatcounters --postgres/no-debug-info --postgres/no-assertions',
            extraArgs: '--with-libstatcounters --postgres/no-debug-info --postgres/assertions --postgres/linkage=dynamic',
            beforeTarball: cleanupScript,
            skipArchiving: true,
            testScript: 'cd /opt/$CPU/ && sh -xe ./run-postgres-tests.sh',
            beforeBuild: 'ls -la $WORKSPACE',
            // Postgres tests need the full disk image (they invoke diff -u)
            minimalTestImage: false,
            testTimeout: 4 * 60 * 60, // increase the test timeout to 4 hours (CHERI can take a loooong time)
            /* sequential: true, // for now run all in order until we have it stable */)
}