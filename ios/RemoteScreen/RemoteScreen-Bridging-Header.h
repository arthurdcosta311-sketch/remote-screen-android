#ifdef ADB_MOBILE

#include <stdlib.h>

// The public surface of adb-mobile (porting/adb/include/adb_public.h in
// adb-mobile-adb_v31.0.3). Declared here rather than included so the app does
// not depend on the library's header search path.
int adb_commandline_porting(int argc, const char** argv, char **message);
char *adb_commandline_last_output(void);

#endif
