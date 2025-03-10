project(ca NONE)

add_subdirectory(shared/conf)
add_subdirectory(${APP_SERVER})

# build pki-ca
javac(pki-ca-classes
    DEPENDS
        pki-certsrv-jar pki-cms-jar
    SOURCES
        src/main/java/*.java
    CLASSPATH
        ${JAXB_API_JAR}
        ${SLF4J_API_JAR}
        ${COMMONS_CLI_JAR}
        ${COMMONS_CODEC_JAR}
        ${COMMONS_IO_JAR}
        ${COMMONS_LANG3_JAR}
        ${COMMONS_NET_JAR}
        ${JACKSON2_CORE_JAR} ${JACKSON2_DATABIND_JAR}
        ${JACKSON2_ANNOTATIONS_JAR}
        ${JSS_JAR} ${JSS_SYMKEY_JAR}
        ${LDAPJDK_JAR}
        ${SERVLET_JAR}
        ${TOMCAT_CATALINA_JAR}
        ${TOMCATJSS_JAR}
        ${JAXRS_API_JAR} ${RESTEASY_JAXRS_JAR}
        ${PKI_TOMCAT_JAR} ${PKI_CERTSRV_JAR} ${PKI_CMS_JAR}
    OUTPUT_DIR
        ${CMAKE_CURRENT_BINARY_DIR}/classes
)

configure_file(
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/resources/META-INF/MANIFEST.MF
    ${CMAKE_CURRENT_BINARY_DIR}/MANIFEST.MF
)

jar(pki-ca-jar
    DEPENDS
        pki-ca-classes
    CREATE
        ${CMAKE_BINARY_DIR}/dist/pki-ca.jar
    OPTIONS
        m
    PARAMS
        ${CMAKE_CURRENT_BINARY_DIR}/MANIFEST.MF
    INPUT_DIR
        ${CMAKE_CURRENT_BINARY_DIR}/classes
)

# Create links at /usr/share/pki/ca/webapps/ca/admin/console.
# Create links in /usr/share/pki/ca/webapps/ca/WEB-INF/lib.
# This can be customized for different platforms in RPM spec.

add_custom_target(pki-ca-links ALL)

add_custom_command(
    TARGET pki-ca-links
    COMMAND ${CMAKE_COMMAND} -E make_directory webapp/admin
    COMMAND ln -sf ../../../../../../..${DATA_INSTALL_DIR}/server/webapps/pki/admin/console ${CMAKE_CURRENT_BINARY_DIR}/webapp/admin
    COMMAND ${CMAKE_COMMAND} -E make_directory webapp/lib
    COMMAND ln -sf ../../../../../../../..${SLF4J_API_JAR} webapp/lib/slf4j-api.jar
    COMMAND ln -sf ../../../../../../../..${SLF4J_JDK14_JAR} webapp/lib/slf4j-jdk14.jar
    COMMAND ln -sf ../../../../../../../..${JAVA_JAR_INSTALL_DIR}/pki/pki-certsrv.jar ${CMAKE_CURRENT_BINARY_DIR}/webapp/lib/pki-certsrv.jar
    COMMAND ln -sf ../../../../../../../..${JAVA_JAR_INSTALL_DIR}/pki/pki-cms.jar ${CMAKE_CURRENT_BINARY_DIR}/webapp/lib/pki-cms.jar
    COMMAND ln -sf ../../../../../../../..${JAVA_JAR_INSTALL_DIR}/pki/pki-ca.jar ${CMAKE_CURRENT_BINARY_DIR}/webapp/lib/pki-ca.jar
)

install(
    FILES
        ${CMAKE_BINARY_DIR}/dist/pki-ca.jar
    DESTINATION
        ${JAVA_JAR_INSTALL_DIR}/pki
)

set(PKI_CA_JAR ${CMAKE_BINARY_DIR}/dist/pki-ca.jar CACHE INTERNAL "pki-ca jar file")

# install directories
install(
    DIRECTORY
        auth/
    DESTINATION
        ${DATA_INSTALL_DIR}/ca/auth
)

install(
    DIRECTORY
        bin/
    DESTINATION
        ${DATA_INSTALL_DIR}/ca/bin
    FILE_PERMISSIONS
        OWNER_EXECUTE OWNER_READ
        GROUP_EXECUTE GROUP_READ
        WORLD_EXECUTE WORLD_READ
)

install(
    DIRECTORY
        database/
    DESTINATION
        ${DATA_INSTALL_DIR}/ca/database
)

install(
    DIRECTORY
        shared/
    DESTINATION
        ${SHARE_INSTALL_PREFIX}/${APPLICATION_NAME}/${PROJECT_NAME}
    PATTERN
        "CMakeLists.txt" EXCLUDE
)

install(
    DIRECTORY
        ${CMAKE_CURRENT_BINARY_DIR}/webapp/admin/
    DESTINATION
        ${DATA_INSTALL_DIR}/ca/webapps/ca/admin
)

install(
    DIRECTORY
        ${CMAKE_CURRENT_BINARY_DIR}/webapp/lib/
    DESTINATION
        ${DATA_INSTALL_DIR}/ca/webapps/ca/WEB-INF/lib
)

# install empty directories
install(
    DIRECTORY
    DESTINATION
        ${VAR_INSTALL_DIR}/lock/pki/ca
)

install(
    DIRECTORY
    DESTINATION
        ${VAR_INSTALL_DIR}/run/pki/ca
)
