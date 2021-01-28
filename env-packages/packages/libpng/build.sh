PACKAGE_VERSION="1.6.37"
PACKAGE_SRCURL="https://downloads.sourceforge.net/sourceforge/libpng/libpng-${PACKAGE_VERSION}.tar.xz"
PACKAGE_SHA256="505e70834d35383537b6491e7ae8641f1a4bed1876dbfe361201fc80868d88ca"
PACKAGE_DEPENDS="zlib"

builder_step_pre_configure() {
	rm -f CMakeLists.txt
}
