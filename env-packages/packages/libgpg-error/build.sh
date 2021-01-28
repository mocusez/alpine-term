PACKAGE_VERSION="1.39"
PACKAGE_SRCURL="https://www.gnupg.org/ftp/gcrypt/libgpg-error/libgpg-error-${PACKAGE_VERSION}.tar.bz2"
PACKAGE_SHA256="4a836edcae592094ef1c5a4834908f44986ab2b82e0824a0344b49df8cdb298f"

builder_step_post_extract_package() {
	# Upstream only has Android definitions for platform-specific lock objects.
	# See https://lists.gnupg.org/pipermail/gnupg-devel/2014-January/028203.html
	# for how to generate a lock-obj header file on devices.

	# For aarch64 this was generated on a device:
	cp "$PACKAGE_BUILDER_DIR"/lock-obj-pub.aarch64-unknown-linux-android.h \
		"$PACKAGE_SRCDIR"/src/syscfg/

	if [ "$PACKAGE_TARGET_ARCH" = "i686" ]; then
		# Android i686 has same config as arm (verified by generating a file on a i686 device):
		cp "$PACKAGE_SRCDIR"/src/syscfg/lock-obj-pub.arm-unknown-linux-androideabi.h \
		   "$PACKAGE_SRCDIR"/src/syscfg/lock-obj-pub.linux-android.h
	elif [ "$PACKAGE_TARGET_ARCH" = "x86_64" ]; then
		# FIXME: Generate on device.
		cp "$PACKAGE_BUILDER_DIR"/lock-obj-pub.aarch64-unknown-linux-android.h \
			"$PACKAGE_SRCDIR"/src/syscfg/lock-obj-pub.linux-android.h
	fi
}

builder_step_pre_configure() {
	autoreconf -fi
	# USE_POSIX_THREADS_WEAK is being enabled for on-device build and causes
	# errors, so force-disable it.
	sed -i 's/USE_POSIX_THREADS_WEAK/DONT_USE_POSIX_THREADS_WEAK/g' configure
}
