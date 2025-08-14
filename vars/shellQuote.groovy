String call(String s) {
    // Safe character set according to Python 3.13
    // NB: Empty string needs quoting too
    if (s ==~ /[\w@%+=:,.\/-]+/)
        return s
    return "'" + s.replaceAll('\'', '\\\'') + "'"
}
