@Library('ctsrd-jenkins-scripts') _

properties([disableConcurrentBuilds(),
        disableResume(),
        [$class: 'GithubProjectProperty', displayName: '', projectUrlStr: 'https://github.com/CTSRD-CHERI/llvm/'],
        [$class: 'CopyArtifactPermissionProperty', projectNames: '*'],
        [$class: 'JobPropertyImpl', throttle: [count: 2, durationName: 'hour', userBoost: true]],
        pipelineTriggers([githubPush()])
])


def nodeLabel = null
if (env.JOB_NAME.toLowerCase().contains("linux")) {
    nodeLabel = "linux"
} else if (env.JOB_NAME.toLowerCase().contains("freebsd")) {
    nodeLabel = "freebsd"
} else {/**/
    error("Invalid job name: ${env.JOB_NAME}")
}

node(nodeLabel) {

    if (false) {
        stage("Print env") {
            env2 = env.getEnvironment()
            for (entry in env2) {
                echo("${entry}")
            }
        }
    }
    def llvmRepo = null
    def clangRepo = null
    def lldRepo = null
    def llvmBranch = env.BRANCH_NAME
    def clangBranch = llvmBranch
    def lldBranch = llvmBranch == 'cap-table' ? 'master' : llvmBranch
    def gitCredentials = null

    // TODO: proper time limit, etc:
    // checkout([$class: 'GitSCM', branches: [[name: '*/asdas']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', noTags: false, reference: '', shallow: true, timeout: 60]], submoduleCfg: [], userRemoteConfigs: [[url: 'dasdasda']]])

    // TODO: special case for cap-table where lld uses master and not cap-table
    stage("Checkout sources") {
        echo("scm=${scm}")
        dir ("llvm") {
            llvmRepo = git(url: 'https://github.com/CTSRD-CHERI/llvm', branch: llvmBranch,
                    changelog: true, credentialsId: gitCredentials, poll: true)
            echo("LLVM = ${llvmRepo}")
        }
        dir ("llvm/tools/clang") {
            clangRepo = git(url: 'https://github.com/CTSRD-CHERI/llvm', branch: clangBranch,
                    changelog: true, credentialsId: gitCredentials, poll: true)
            echo("CLANG = ${clangRepo}")
        }
        dir ("llvm/tools/lld") {
            lldRepo = git(url: 'https://github.com/CTSRD-CHERI/lld', branch: lldBranch,
                    changelog: true, credentialsId: gitCredentials, poll: true)
            echo("LLD = ${lldRepo}")
        }
    }
    env.LLVM_ARTIFACT = "cheri-${llvmBranch}-clang-llvm.tar.xz"
    env.SDKROOT = "${env.WORKSPACE}/sdk"
    env.label = nodeLabel

    stage("Build") {
        sh '''#!/usr/bin/env bash 
set -xe

#remove old artifact
rm -fv "$LLVM_ARTIFACT"

if [ -e "${WORKSPACE}/sdk" ]; then
   echo "ERROR, old SDK was not deleted!" && exit 1
fi
# if [ -e "${WORKSPACE}/llvm/Build" ]; then
#   echo "ERROR, old build was not deleted!" && exit 1
# fi

# go to llvm, checkout the appropriate branch and create the Build directory
git -C "${WORKSPACE}/llvm" rev-parse HEAD
git -C "${WORKSPACE}/llvm/tools/clang" rev-parse HEAD
git -C "${WORKSPACE}/llvm/tools/lld" rev-parse HEAD

cd "${WORKSPACE}/llvm" || exit 1
mkdir -p Build

# run cmake
cd Build || exit 1
CMAKE_ARGS=("-DCMAKE_INSTALL_PREFIX=${SDKROOT_DIR}" "-DLLVM_OPTIMIZED_TABLEGEN=OFF")
if [ "$label" == "linux" ] ; then
    #export LIBCXX=/local/scratch/jenkins/libc++
    #export LIBCXX_LIB=$LIBCXX/Release/lib
    #export LIBSTDCXX=/usr/include/c++/4.6
    #export LD_LIBRARY_PATH=$LIBCXX/Release/lib
    #export CMAKE_CXX_FLAGS="-std=c++11 -stdlib=libc++ -isystem $LIBCXX/src/include -isystem $LIBSTDCXX -isystem $LIBSTDCXX/x86_64-linux-gnu"
    #export CMAKE_FLAGS="${CMAKE_FLAGS} -DLLVM_NO_OLD_LIBSTDCXX=YES"
    export CMAKE_CXX_COMPILER=clang++-4.0
    export CMAKE_C_COMPILER=clang-4.0
else
    export CMAKE_CXX_COMPILER=clang++40
    export CMAKE_C_COMPILER=clang40
fi
CMAKE_ARGS+=("-DCMAKE_CXX_COMPILER=${CMAKE_CXX_COMPILER}" "-DCMAKE_C_COMPILER=${CMAKE_C_COMPILER}" "-DLLVM_ENABLE_LLD=ON")

# Release build with assertions is a bit faster than a debug build and A LOT smaller
CMAKE_ARGS+=("-DCMAKE_BUILD_TYPE=Release" "-DLLVM_ENABLE_ASSERTIONS=ON")
# Also don't set the default target or default sysroot when running tests as it breaks quite a few
# max 1 hour total and max 2 minutes per test
CMAKE_ARGS+=("-DLLVM_LIT_ARGS=--xunit-xml-output ${WORKSPACE}/llvm-test-output.xml --max-time 3600 --timeout 240")

rm -f CMakeCache.txt
cmake -G Ninja "${CMAKE_ARGS[@]}" ..

# build
echo ninja -v ${JFLAG}

# install
echo ninja install
'''
    }
    stage("Run tests (128)") {
        sh '''#!/usr/bin/env bash 
set -xe

pwd
cd ${WORKSPACE}/llvm/Build
# run tests
rm -fv "${WORKSPACE}/llvm-test-output.xml"
ninja check-all-cheri128 ${JFLAG} || echo "Some CHERI128 tests failed!"
mv -fv "${WORKSPACE}/llvm-test-output.xml" "${WORKSPACE}/llvm-test-output-cheri128.xml"
echo "Done running 128 tests"

'''
    }
    stage("Run tests (256)") {
        sh '''#!/usr/bin/env bash 
set -xe

pwd
cd ${WORKSPACE}/llvm/Build
rm -fv "${WORKSPACE}/llvm-test-output.xml"
ninja check-all-cheri256 ${JFLAG} || echo "Some CHERI256 tests failed!"
mv -fv "${WORKSPACE}/llvm-test-output.xml" "${WORKSPACE}/llvm-test-output-cheri256.xml"
echo "Done running 256 tests"
'''
    }
    stage("Archive artifacts") {
        sh '''#!/usr/bin/env bash 
set -xe

du -sh "${SDKROOT_DIR}"

# create links for the various tools
cd $SDKROOT_DIR/bin
TOOLS="clang clang++ clang-cpp llvm-mc llvm-objdump llvm-readobj llvm-size ld.lld"
for TOOL in $TOOLS ; do
    ln -fs $TOOL cheri-unknown-freebsd-$TOOL
    ln -fs $TOOL mips4-unknown-freebsd-$TOOL
    ln -fs $TOOL mips64-unknown-freebsd-$TOOL
done
# cc, c++ and cpp symlinks are expected by e.g. Qt
ln -fs clang mips64-unknown-freebsd-cc
ln -fs clang cheri-unknown-freebsd-cc
ln -fs clang++ mips64-unknown-freebsd-c++
ln -fs clang++ cheri-unknown-freebsd-c++
ln -fs clang-cpp mips64-unknown-freebsd-cpp
ln -fs clang-cpp cheri-unknown-freebsd-cpp

# clean & bundle up
cd ${WORKSPACE}
tar -cJf cheri-$BRANCH-clang-include.tar.xz -C ${SDKROOT_DIR} lib/clang
# We can remove all the libraries because we link them statically (but they need to exist)
truncate -s 0 ${SDKROOT_DIR}/lib/lib*
# remove the binaries that are not needed by downstream jobs (saves a lot of archiving and unpacking time)
(cd ${SDKROOT_DIR}/bin && rm -vf clang-check opt llc lli llvm-lto2 llvm-lto llvm-c-test \\
         llvm-dsymutil llvm-dwp llvm-nm llvm-ar llvm-rtdyld \\
         llvm-extract llvm-xray llvm-split llvm-cov llvm-symbolizer llvm-dwarfdump \\
         llvm-link llvm-stress llvm-cxxdump llvm-cvtres llvm-cat llvm-as \\
         llvm-diff llvm-modextract llvm-dis llvm-pdbdump llvm-profdata \\
         llvm-opt-report llvm-bcanalyzer llvm-mcmarkup llvm-lib llvm-ranlib \\
         verify-uselistorder sanstats clang-offload-bundler c-index-test \\
         clang-import-test bugpoint sancov obj2yaml yaml2obj)
# Cmake files need tblgen
truncate -s 0 ${SDKROOT_DIR}/bin/llvm-tblgen
# remove more useless stuff
rm -rf ${SDKROOT_DIR}/share
rm -rf ${SDKROOT_DIR}/include
cd ${SDKROOT_DIR}/..
tar -cJf $LLVM_ARCHIVE `basename ${SDKROOT_DIR}`

# clean up to save some disk space
# rm -rf "${WORKSPACE}/llvm/Build"
rm -rf "$SDKROOT_DIR"
'''
    }

}
