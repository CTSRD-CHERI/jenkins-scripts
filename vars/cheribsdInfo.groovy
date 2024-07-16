def getDefaultKernelAbi(String branch = "main") {
    if (branch == "releng/22.12" || branch == "releng/23.11" || branch == "releng/24.05" || branch == "main")
        return "hybrid"
    return "purecap"
}

def getKernelConfig(String arch, String abi, String branch = "main") {
    if (arch == "morello-hybrid" || arch == "morello-purecap") {
        if (abi == "hybrid")
            return "GENERIC-MORELLO"
        if (abi == "purecap")
            return "GENERIC-MORELLO-PURECAP"
        if (abi == "purecap-benchmark")
            return "GENERIC-MORELLO-PURECAP-BENCHMARK"
        error("Unknown ${arch} kernel ABI: '${abi}'")
        return null
    }
    if (arch == "riscv64-hybrid" || arch == "riscv64-purecap") {
        if (abi == "hybrid")
            return "CHERI-QEMU"
        if (abi == "purecap")
            return "CHERI-PURECAP-QEMU"
        error("Unknown ${arch} kernel ABI: '${abi}'")
        return null
    }
    error("Unknown architecture: '${arch}'")
}
