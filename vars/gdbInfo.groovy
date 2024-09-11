def getDefaultBranch(boolean kgdb = false) {
    if (kgdb)
        return "cheri-14-kgdb"
    return "cheri-14"
}
