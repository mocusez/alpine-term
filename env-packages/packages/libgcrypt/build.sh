PACKAGE_VERSION="1.8.7"
PACKAGE_SRCURL="https://www.gnupg.org/ftp/gcrypt/libgcrypt/libgcrypt-${PACKAGE_VERSION}.tar.bz2"
PACKAGE_SHA256="03b70f028299561b7034b8966d7dd77ef16ed139c43440925fe8782561974748"
PACKAGE_DEPENDS="libgpg-error"

PACKAGE_EXTRA_CONFIGURE_ARGS="
ac_cv_lib_pthread_pthread_create=yes
gcry_cv_gcc_inline_asm_neon=no
--disable-jent-support
--disable-asm
"

termux_step_pre_configure() {
	CFLAGS+=" -no-integrated-as"

	if [ "$PACKAGE_TARGET_ARCH" = "arm" ]; then
		# See http://marc.info/?l=gnupg-devel&m=139136972631909&w=3
		CFLAGS+=" -mno-unaligned-access"
	fi
}
