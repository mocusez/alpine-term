PACKAGE_VERSION="1.1.1h"
PACKAGE_SRCURL="https://www.openssl.org/source/openssl-${PACKAGE_VERSION/\~/-}.tar.gz"
PACKAGE_SHA256="5c9ca8774bd7b03e5784f26ae9e9e6d749c9da2438545077e6b3d755a06595d9"
PACKAGE_BUILD_IN_SRC="true"

builder_step_configure() {
	CFLAGS+=" -DNO_SYSLOG"
	if [ "$PACKAGE_TARGET_ARCH" = "arm" ]; then
		CFLAGS+=" -fno-integrated-as"
	fi

	perl -p -i -e "s@TERMUX_CFLAGS@$CFLAGS@g" Configure

	rm -rf "$PACKAGE_INSTALL_PREFIX"/lib/libcrypto.*
	rm -rf "$PACKAGE_INSTALL_PREFIX"/lib/libssl.*

	test "$PACKAGE_TARGET_ARCH" = "arm" && TERMUX_OPENSSL_PLATFORM="android-arm"
	test "$PACKAGE_TARGET_ARCH" = "aarch64" && TERMUX_OPENSSL_PLATFORM="android-arm64"
	test "$PACKAGE_TARGET_ARCH" = "i686" && TERMUX_OPENSSL_PLATFORM="android-x86"
	test "$PACKAGE_TARGET_ARCH" = "x86_64" && TERMUX_OPENSSL_PLATFORM="android-x86_64"

	./Configure "$TERMUX_OPENSSL_PLATFORM" \
		--prefix="$PACKAGE_INSTALL_PREFIX" \
		--openssldir="$PACKAGE_INSTALL_PREFIX" \
		no-shared \
		no-ssl \
		no-comp \
		no-dso \
		no-hw \
		no-engine \
		no-srp \
		no-tests
}

builder_step_make() {
	make depend
	make -j "$CONFIG_BUILDER_MAKE_PROCESSES" all
}
