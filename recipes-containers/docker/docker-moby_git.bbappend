# bbappend to modify the SRC_URI for docker-moby recipe

GO_SRCURI_DESTSUFFIX = "${S}/src/import"

# Remove existing SRC_URI entry for moby git repository
SRC_URI:remove = "git://github.com/moby/moby.git;branch=25.0;name=moby;protocol=https"

# Add modified SRC_URI entry for moby git repository with custom destsuffix
SRC_URI:append = " git://github.com/moby/moby.git;branch=25.0;name=moby;protocol=https;destsuffix=${GO_SRCURI_DESTSUFFIX}"
