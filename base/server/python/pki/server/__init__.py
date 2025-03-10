# Authors:
#     Endi S. Dewata <edewata@redhat.com>
#     Dinesh Prasanth M K <dmoluguw@redhat.com>
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation; version 2 of the License.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along
# with this program; if not, write to the Free Software Foundation, Inc.,
# 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Copyright (C) 2018 Red Hat, Inc.
# All rights reserved.
#

from __future__ import absolute_import

import datetime
import functools
import getpass
import grp
import inspect
import logging
import os
import pathlib
import pwd
import re
import requests
import shutil
import subprocess
import tempfile
import time
import socket

import ldap
import ldap.filter
from lxml import etree

import pki
import pki.account
import pki.cert
import pki.client
import pki.nssdb
import pki.util
from pki.keyring import Keyring
import pki.server.subsystem

SYSCONFIG_DIR = '/etc/sysconfig'
ETC_SYSTEMD_DIR = '/etc/systemd'
LIB_SYSTEMD_DIR = '/lib/systemd'

SUBSYSTEM_TYPES = ['ca', 'kra', 'ocsp', 'tks', 'tps']

DEFAULT_DIR_MODE = 0o0770
DEFAULT_FILE_MODE = 0o0660
DEFAULT_LINK_MODE = 0o0777

SCHEMA_FILES = [
    '/usr/share/pki/server/database/ds/schema.ldif'
]

logger = logging.getLogger(__name__)

parser = etree.XMLParser(remove_blank_text=True)


class Tomcat(object):

    BASE_DIR = '/var/lib/tomcats'
    CONF_DIR = '/etc/tomcat'
    LIB_DIR = '/usr/share/java/tomcat'
    SHARE_DIR = '/usr/share/tomcat'
    EXECUTABLE = '/usr/sbin/tomcat'
    UNIT_FILE = '/lib/systemd/system/tomcat@.service'
    TOMCAT_CONF = CONF_DIR + '/tomcat.conf'

    @classmethod
    def get_version(cls):
        # run "tomcat version"
        output = subprocess.check_output([Tomcat.EXECUTABLE, 'version'])
        output = output.decode('utf-8')

        # find "Server version: Apache Tomcat/<version>"
        match = re.search(
            r'^Server version: *.*/(.+)$',
            output,
            re.MULTILINE  # pylint: disable=no-member
        )

        if not match:
            raise Exception('Unable to determine Tomcat version')

        # return version
        return pki.util.Version(match.group(1))


@functools.total_ordering
class PKIServer(object):

    BASE_DIR = '/var/lib/pki'
    CONFIG_DIR = '/etc/pki'
    LOG_DIR = '/var/log/pki'
    SHARE_DIR = '/usr/share/pki'
    REGISTRY_DIR = SYSCONFIG_DIR + '/pki'
    TOMCAT_CONF = SHARE_DIR + '/etc/tomcat.conf'

    def __init__(self,
                 name,
                 instance_type='tomcat',
                 user='tomcat',
                 group='tomcat'):

        self.name = name
        self.type = instance_type
        self.user = user
        self.group = group

        self.config = {}
        self.passwords = {}
        self.subsystems = {}

    def __repr__(self):
        return self.name

    def __hash__(self):
        return hash(self.name)

    def __eq__(self, other):
        if not isinstance(other, PKIServer):
            return NotImplemented
        return self.name == other.name

    def __lt__(self, other):
        if not isinstance(other, PKIServer):
            return NotImplemented
        return self.name < other.name

    @property
    def base_dir(self):
        return os.path.join(Tomcat.BASE_DIR, self.name)

    @property
    def bin_dir(self):
        return os.path.join(self.base_dir, 'bin')

    @property
    def conf_dir(self):
        return os.path.join(self.base_dir, 'conf')

    @property
    def lib_dir(self):
        return os.path.join(self.base_dir, 'lib')

    @property
    def common_dir(self):
        return os.path.join(self.base_dir, 'common')

    @property
    def common_lib_dir(self):
        return os.path.join(self.common_dir, 'lib')

    @property
    def log_dir(self):
        return os.path.join(self.base_dir, 'logs')

    @property
    def temp_dir(self):
        return os.path.join(self.base_dir, 'temp')

    @property
    def webapps_dir(self):
        return os.path.join(self.base_dir, 'webapps')

    @property
    def work_dir(self):
        return os.path.join(self.base_dir, 'work')

    @property
    def catalina_policy(self):
        return os.path.join(self.conf_dir, 'catalina.policy')

    @property
    def catalina_properties(self):
        return os.path.join(self.conf_dir, 'catalina.properties')

    @property
    def context_xml(self):
        return os.path.join(self.conf_dir, 'context.xml')

    @property
    def logging_properties(self):
        return os.path.join(self.conf_dir, 'logging.properties')

    @property
    def server_xml(self):
        return os.path.join(self.conf_dir, 'server.xml')

    @property
    def tomcat_conf(self):
        return os.path.join(self.conf_dir, 'tomcat.conf')

    @property
    def web_xml(self):
        return os.path.join(self.conf_dir, 'web.xml')

    @property
    def service_name(self):
        return '%s@%s' % (self.type, self.name)

    @property
    def service_conf(self):
        return os.path.join(SYSCONFIG_DIR, self.service_name)

    @property
    def uid(self):
        return pwd.getpwnam(self.user).pw_uid

    @property
    def gid(self):
        return grp.getgrnam(self.group).gr_gid

    @property
    def password_conf(self):
        return os.path.join(self.conf_dir, 'password.conf')

    @property
    def nssdb_dir(self):
        return os.path.join(self.conf_dir, 'alias')

    @property
    def nssdb_link(self):
        return os.path.join(self.base_dir, 'alias')

    @property
    def jss_conf(self):
        return os.path.join(self.conf_dir, 'jss.conf')

    @property
    def ca_cert(self):
        return os.path.join(self.nssdb_dir, 'ca.crt')

    def is_valid(self):
        return self.exists()

    def exists(self):
        return os.path.exists(self.base_dir)

    def validate(self):
        if not self.exists():
            raise pki.PKIException('Invalid instance: ' + self.name, None)

    def is_active(self):
        cmd = ['systemctl', '--quiet', 'is-active', '%s.service' % self.service_name]
        logger.debug('Command: %s', ' '.join(cmd))
        rc = subprocess.call(cmd)
        return rc == 0

    def export_ca_cert(self):

        server_config = self.get_server_config()
        connector = server_config.get_connector('Secure')

        if connector is None:
            # HTTPS connector not configured, skip
            return

        sslhost = server_config.get_sslhost(connector)
        sslcert = server_config.get_sslcert(sslhost)

        keystore_type = sslcert.get('certificateKeystoreType')
        keystore_provider = sslcert.get('certificateKeystoreProvider')

        if keystore_type == 'pkcs11' and keystore_provider == 'Mozilla-JSS':

            # export CA cert from NSS database

            token = pki.nssdb.INTERNAL_TOKEN_NAME
            nickname = self.get_sslserver_cert_nickname()

            if nickname is None:
                return

            if ':' in nickname:
                parts = nickname.split(':', 1)
                token = parts[0]
                nickname = parts[1]

            nssdb = self.open_nssdb(token=token)
            try:
                nssdb.extract_ca_cert(self.ca_cert, nickname)
            finally:
                nssdb.close()

        # TODO: handle other types of HTTP connector

    def create_catalina_policy(self):

        logger.info('Creating catalina.policy')

        filename = '/usr/share/pki/server/conf/catalina.policy'
        logger.info('Appending %s', filename)
        with open(filename, 'r', encoding='utf-8') as f:
            content = f.read()

        filename = '/usr/share/tomcat/conf/catalina.policy'
        logger.info('Appending %s', filename)
        with open(filename, 'r', encoding='utf-8') as f:
            content += f.read()

        content += '\n\n'

        filename = '/usr/share/pki/server/conf/pki.policy'
        logger.info('Appending %s', filename)
        with open(filename, 'r', encoding='utf-8') as f:
            content += f.read()

        for root, _, filenames in os.walk(self.common_lib_dir):
            for filename in filenames:
                filepath = os.path.join(root, filename)
                logger.info('Adding policy for %s', filepath)
                content += '''
grant codeBase "file:%s" {
    permission java.security.AllPermission;
};
''' % filepath

        filename = '%s/custom.policy' % self.conf_dir
        if os.path.exists(filename):
            logger.info('Appending %s', filename)
            content += '\n'
            with open(filename, 'r', encoding='utf-8') as f:
                content += f.read()

        filename = '%s/catalina.policy' % self.conf_dir
        logger.info('Storing %s', filename)
        with open(filename, 'w', encoding='utf-8') as f:
            f.write(content)

    def init(self):

        self.export_ca_cert()

        if os.environ.get('PKI_SERVER_AUTO_ENABLE_SUBSYSTEMS', 'true') == 'true':
            self.enable_subsystems()

        self.create_catalina_policy()

    def is_running(self, timeout=None):

        server_config = self.get_server_config()

        protocol = 'https'
        hostname = socket.getfqdn()
        port = server_config.get_secure_port()

        if port is None:
            protocol = 'http'
            port = server_config.get_unsecure_port()

        connection = pki.client.PKIConnection(
            protocol=protocol,
            hostname=hostname,
            port=port,
            trust_env=False,
            verify=False)

        try:
            connection.get('/', timeout=timeout)

            # the path exists and the server is running
            return True

        except requests.exceptions.HTTPError as e:
            if e.response.status_code == 404:
                # the path does not exist but the server is running
                return True

            # the server is not running
            raise

    def start(self, wait=False, max_wait=60, timeout=None):

        cmd = ['systemctl', 'start', '%s.service' % self.service_name]
        logger.debug('Command: %s', ' '.join(cmd))
        subprocess.check_call(cmd)

        if not wait:
            return

        logger.info('Waiting for PKI server to start')

        start_time = datetime.datetime.today()
        started = False
        counter = 0

        while not started:
            try:
                time.sleep(1)
                started = self.is_running(timeout=timeout)

            except requests.exceptions.SSLError as e:
                max_retry_error = e.args[0]
                reason = getattr(max_retry_error, 'reason')
                raise Exception('Server unreachable due to SSL error: %s' % reason) from e

            except pki.RETRYABLE_EXCEPTIONS as e:

                stop_time = datetime.datetime.today()
                counter = (stop_time - start_time).total_seconds()

                if max_wait is not None and counter >= max_wait:
                    raise Exception('Server did not start after %ds' %
                                    max_wait) from e

                logger.info(
                    'Waiting for PKI server to start (%ds)',
                    int(round(counter)))

        logger.info('PKI server started')

    def stop(self, wait=False, max_wait=60, timeout=None):

        cmd = ['systemctl', 'stop', '%s.service' % self.service_name]
        logger.debug('Command: %s', ' '.join(cmd))
        subprocess.check_call(cmd)

        if not wait:
            return

        logger.info('Waiting for PKI server to stop')

        start_time = datetime.datetime.today()
        stopped = False
        counter = 0

        while not stopped:
            try:
                time.sleep(1)
                stopped = not self.is_running(timeout=timeout)

            except requests.exceptions.SSLError as e:
                max_retry_error = e.args[0]
                reason = getattr(max_retry_error, 'reason')
                raise Exception('Server unreachable due to SSL error: %s' % reason) from e

            except requests.exceptions.ConnectionError:
                stopped = True

            except pki.RETRYABLE_EXCEPTIONS as e:

                stop_time = datetime.datetime.today()
                counter = (stop_time - start_time).total_seconds()

                if max_wait is not None and counter >= max_wait:
                    raise Exception('Server did not stop after %ds' %
                                    max_wait) from e

                logger.info(
                    'Waiting for PKI server to stop (%ds)',
                    int(round(counter)))

        logger.info('PKI server stopped')

    def restart(self, wait=False, max_wait=60, timeout=None):
        self.stop(wait=True, max_wait=max_wait, timeout=timeout)
        self.start(wait=wait, max_wait=max_wait, timeout=timeout)

    def enable(self):
        cmd = ['systemctl', 'enable', '%s.service' % self.service_name]
        logger.debug('Command: %s', ' '.join(cmd))
        subprocess.check_call(cmd)

    def disable(self):
        cmd = ['systemctl', 'disable', '%s.service' % self.service_name]
        logger.debug('Command: %s', ' '.join(cmd))
        subprocess.check_call(cmd)

    def run(self, command='start',
            as_current_user=False,
            with_jdb=False,
            with_gdb=False,
            with_valgrind=False,
            agentpath=None):

        p = self.execute(
            command,
            as_current_user=as_current_user,
            with_jdb=with_jdb,
            with_gdb=with_gdb,
            with_valgrind=with_valgrind,
            agentpath=agentpath)

        p.wait()

    def execute(
            self, command,
            as_current_user=False,
            with_jdb=False,
            with_gdb=False,
            with_valgrind=False,
            agentpath=None):

        logger.debug('Environment variables:')
        for name in self.config:
            logger.debug('- %s: %s', name, self.config[name])

        prefix = []

        # by default run PKI server as systemd user
        if not as_current_user:

            current_user = pwd.getpwuid(os.getuid()).pw_name

            # switch to systemd user if different from current user
            if current_user != self.user:
                prefix.extend(['/usr/sbin/runuser', '-u', self.user, '--'])

        java_home = self.config.get('JAVA_HOME')
        java_opts = self.config.get('JAVA_OPTS')
        security_manager = self.config.get('SECURITY_MANAGER')

        classpath = [
            Tomcat.SHARE_DIR + '/bin/bootstrap.jar',
            Tomcat.SHARE_DIR + '/bin/tomcat-juli.jar',
            '/usr/lib/java/commons-daemon.jar'
        ]

        cmd = prefix

        if with_valgrind:
            cmd.extend(['valgrind', '--trace-children=yes', '--tool=massif'])

        if with_gdb:
            cmd.extend(['gdb', '--args'])

        if with_jdb:
            cmd.extend(['jdb'])

        else:
            cmd.extend([java_home + '/bin/java'])

            # add JVM options as in /etc/tomcat/conf.d/java-9-start-up-parameters.conf
            cmd.extend([
                '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
                '--add-opens', 'java.base/java.io=ALL-UNNAMED',
                '--add-opens', 'java.base/java.util=ALL-UNNAMED',
                '--add-opens', 'java.base/java.util.concurrent=ALL-UNNAMED',
                '--add-opens', 'java.rmi/sun.rmi.transport=ALL-UNNAMED',
            ])

        if agentpath:
            cmd.extend(['-agentpath:%s' % agentpath])

        elif os.path.exists('/usr/lib/abrt-java-connector/libabrt-java-connector.so'):
            cmd.extend([
                '-agentpath:/usr/lib/abrt-java-connector/libabrt-java-connector.so=abrt=on,'
            ])

        cmd.extend([
            '-classpath', os.pathsep.join(classpath),
            '-Dcatalina.base=' + self.base_dir,
            '-Dcatalina.home=' + Tomcat.SHARE_DIR,
            '-Djava.endorsed.dirs=',
            '-Djava.io.tmpdir=' + self.temp_dir,
            '-Djava.util.logging.config.file=' + self.logging_properties,
            '-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager'
        ])

        if security_manager == 'true':
            cmd.extend([
                '-Djava.security.manager',
                '-Djava.security.policy==' + self.catalina_policy
            ])

        if java_opts:
            cmd.extend(java_opts.split())

        if with_valgrind:
            cmd.extend(['-Djava.compiler=NONE'])

        cmd.extend(['org.apache.catalina.startup.Bootstrap', command])

        logger.debug('Command: %s', ' '.join(cmd))

        return subprocess.Popen(cmd, env=self.config)

    def touch(self, path):
        pathlib.Path(path).touch()
        os.chown(path, self.uid, self.gid)
        os.chmod(path, DEFAULT_FILE_MODE)

    def makedirs(self, path, exist_ok=None, force=False):

        logger.info('Creating %s', path)

        pki.util.makedirs(
            path,
            mode=DEFAULT_DIR_MODE,
            exist_ok=exist_ok,
            uid=self.uid,
            gid=self.gid,
            force=force)

    def symlink(self, source, dest, force=False):

        logger.info('Creating %s', dest)

        pki.util.symlink(
            source, dest, uid=self.uid, gid=self.gid, force=force)

    def copy(self, source, dest, force=False):

        logger.info('Creating %s', dest)

        pki.util.copy(
            source,
            dest,
            uid=self.uid,
            gid=self.gid,
            dir_mode=DEFAULT_DIR_MODE,
            file_mode=DEFAULT_FILE_MODE,
            force=force)

    def copydirs(self, source, dest, force=False):

        logger.info('Creating %s', dest)

        pki.util.copydirs(
            source,
            dest,
            uid=self.uid,
            gid=self.gid,
            mode=DEFAULT_DIR_MODE,
            force=force)

    def copyfile(self, source, dest, slots=None, params=None, force=False):

        logger.info('Creating %s', dest)

        pki.util.copyfile(
            source,
            dest,
            slots=slots,
            params=params,
            uid=self.uid,
            gid=self.gid,
            mode=DEFAULT_FILE_MODE,
            force=force)

    def store_properties(self, filename, properties):
        pki.util.store_properties(filename, properties)
        pki.util.chown(filename, self.uid, self.gid)

    def create(self, force=False):

        self.makedirs(self.base_dir, force=force)

        bin_dir = os.path.join(Tomcat.SHARE_DIR, 'bin')
        self.symlink(bin_dir, self.bin_dir, force=force)

        self.create_conf_dir(force=force)

        catalina_policy = os.path.join(Tomcat.CONF_DIR, 'catalina.policy')
        self.copy(catalina_policy, self.catalina_policy, force=force)

        catalina_properties = os.path.join(
            PKIServer.SHARE_DIR, 'server', 'conf', 'catalina.properties')
        self.symlink(catalina_properties, self.catalina_properties, force=force)

        context_xml = os.path.join(Tomcat.CONF_DIR, 'context.xml')
        self.symlink(context_xml, self.context_xml, force=force)

        self.create_logging_properties(force=force)
        self.create_server_xml()

        # copy /etc/tomcat/tomcat.conf
        self.copy(Tomcat.TOMCAT_CONF, self.tomcat_conf, force=force)

        tomcat_conf = pki.PropertyFile(self.tomcat_conf)
        tomcat_conf.read()

        # store JAVA_HOME from /usr/share/pki/etc/pki.conf
        java_home = os.getenv('JAVA_HOME')
        tomcat_conf.set('JAVA_HOME', java_home)

        # store current PKI version
        tomcat_conf.set('PKI_VERSION', pki.specification_version())

        tomcat_conf.write()

        web_xml = os.path.join(Tomcat.CONF_DIR, 'web.xml')
        self.symlink(web_xml, self.web_xml, force=force)

        self.create_libs(force=force)

        self.makedirs(self.temp_dir, force=force)
        self.makedirs(self.webapps_dir, force=force)
        self.makedirs(self.work_dir, force=force)
        self.makedirs(self.log_dir, force=force)

        document = etree.parse(self.server_xml, parser)
        server = document.getroot()

        for engine in server.findall('Service/Engine'):
            engine_name = engine.get('name')

            engine_dir = os.path.join(self.conf_dir, engine_name)
            self.makedirs(engine_dir, force=force)

            for host in engine.findall('Host'):
                host_name = host.get('name')

                host_dir = os.path.join(engine_dir, host_name)
                self.makedirs(host_dir, force=force)

        service_conf = os.path.join(SYSCONFIG_DIR, 'tomcat')
        self.copy(service_conf, self.service_conf, force=force)

        with open(self.service_conf, 'a', encoding='utf-8') as f:
            print('CATALINA_BASE="%s"' % self.base_dir, file=f)

    def create_conf_dir(self, force=False):

        self.makedirs(self.conf_dir, force=force)

    def create_logging_properties(self, force=False):

        logging_properties = os.path.join(Tomcat.CONF_DIR, 'logging.properties')
        self.copy(logging_properties, self.logging_properties, force=force)

    def create_server_xml(self):

        server_xml = os.path.join(Tomcat.CONF_DIR, 'server.xml')
        document = etree.parse(server_xml, parser)

        self.remove_lockout_realm(document)
        self.remove_default_user_database(document)

        logger.info('Creating %s', self.server_xml)
        with open(self.server_xml, 'wb') as f:
            document.write(f, pretty_print=True, encoding='utf-8')

        pki.util.chown(self.server_xml, self.uid, self.gid)

    def remove_lockout_realm(self, document):

        server = document.getroot()

        logger.info('Searching for LockOutRealm')
        for engine in server.findall('Service/Engine'):

            for realm in engine.findall('Realm'):
                class_name = realm.get('className')

                if class_name != 'org.apache.catalina.realm.LockOutRealm':
                    continue

                logger.info('Searching for nested UserDatabase Realm')
                nested_realm = realm.find('Realm')
                resource_name = nested_realm.get('resourceName')

                if resource_name != 'UserDatabase':
                    logger.info('Nested UserDatabase Realm not found')
                    continue

                logger.info('Removing LockOutRealm')
                engine.remove(realm)

    def remove_default_user_database(self, document):

        server = document.getroot()

        logger.info('Searching for GlobalNamingResources')
        global_naming_resources = server.find('GlobalNamingResources')

        if len(global_naming_resources) == 0:
            logger.info('GlobalNamingResources not found')
            return

        logger.info('Searching for Resources under GlobalNamingResources')
        resources = global_naming_resources.findall('Resource')

        if len(resources) == 0:
            logger.info('No Resources under GlobalNamingResources')
            return

        logger.info('Searching for UserDatabase Resource')

        user_database = None
        for resource in resources:
            name = resource.get('name')
            if name == 'UserDatabase':
                user_database = resource
                break

        if user_database is not None:
            logger.info('Removing UserDatabase Resource')
            global_naming_resources.remove(user_database)

    def create_libs(self, force=False):

        lib_dir = os.path.join(PKIServer.SHARE_DIR, 'server', 'lib')
        self.symlink(lib_dir, self.lib_dir, force=force)

        self.makedirs(self.common_dir, force=force)

        common_lib_dir = os.path.join(PKIServer.SHARE_DIR, 'server', 'common', 'lib')
        self.symlink(common_lib_dir, self.common_lib_dir, force=force)

    def create_nssdb(self, force=False):

        logger.info('Creating %s', self.nssdb_dir)

        if force and os.path.exists(self.nssdb_dir):
            logger.warning('NSS database already exists: %s', self.nssdb_dir)
            return

        self.makedirs(self.nssdb_dir, force=force)

        self.symlink(self.nssdb_dir, self.nssdb_link, force=force)

        password = self.passwords.get(pki.nssdb.INTERNAL_TOKEN_NAME)

        nssdb = pki.nssdb.NSSDatabase(
            directory=self.nssdb_dir,
            password=password)

        try:
            nssdb.create()
        finally:
            nssdb.close()

        pki.util.chown(self.nssdb_dir, self.uid, self.gid)

    def open_nssdb(self, token=pki.nssdb.INTERNAL_TOKEN_NAME,
                   user=None, group=None):
        return pki.nssdb.NSSDatabase(
            directory=self.nssdb_dir,
            token=token,
            password=self.get_token_password(token),
            internal_password=self.get_token_password(),
            passwords=self.passwords,
            user=user,
            group=group)

    def get_webapps(self):

        webapps = []

        context_dir = os.path.join(
            self.conf_dir,
            'Catalina',
            'localhost')

        for filename in os.listdir(context_dir):

            if not filename.endswith('.xml'):
                continue

            webapp = {}

            webapp_id = filename[:-4]
            webapp['id'] = webapp_id

            parts = webapp_id.split('##')

            name = parts[0]
            if name == 'ROOT':
                webapp['path'] = '/'
            else:
                webapp['path'] = '/' + name.replace('#', '/')

            if len(parts) > 1:
                webapp['version'] = parts[1]

            context_xml = os.path.join(context_dir, filename)
            webapp['descriptor'] = context_xml

            document = etree.parse(context_xml, parser)
            context = document.getroot()

            doc_base = context.get('docBase')
            webapp['docBase'] = doc_base

            webapps.append(webapp)

        return sorted(webapps, key=lambda webapp: webapp['id'])

    def is_deployed(self, webapp_id):

        context_xml = os.path.join(
            self.conf_dir,
            'Catalina',
            'localhost',
            webapp_id + '.xml')

        return os.path.exists(context_xml)

    def is_available(self, path='/', timeout=None):

        server_config = self.get_server_config()

        protocol = 'https'
        hostname = socket.getfqdn()
        port = server_config.get_secure_port()

        if port is None:
            protocol = 'http'
            port = server_config.get_unsecure_port()

        connection = pki.client.PKIConnection(
            protocol=protocol,
            hostname=hostname,
            port=port,
            trust_env=False,
            verify=False)

        try:
            connection.get(path, timeout=timeout)
            return True

        except requests.exceptions.HTTPError as e:
            if e.response.status_code == 404:
                return False
            raise

    def deploy_webapp(
            self,
            webapp_id,
            descriptor,
            doc_base=None,
            wait=False,
            max_wait=60,
            timeout=None):
        """
        Deploy a web application into a Tomcat instance.

        This method will copy the specified deployment descriptor into
        <instance>/conf/Catalina/localhost/<name>.xml and point the docBase
        to the specified location. The web application will become available
        under "/<name>" URL path.

        See also: https://tomcat.apache.org/tomcat-9.0-doc/config/context.html

        :param webapp_id: Web application ID.
        :type webapp_id: str
        :param descriptor: Path to deployment descriptor (context.xml).
        :type descriptor: str
        :param doc_base: Path to web application content.
        :type doc_base: str
        """

        context_xml = os.path.join(
            self.conf_dir,
            'Catalina',
            'localhost',
            webapp_id + '.xml')

        # read deployment descriptor
        document = etree.parse(descriptor, parser)

        if doc_base:
            # customize docBase
            context = document.getroot()
            context.set('docBase', doc_base)

        logger.info('Creating %s', context_xml)
        with open(context_xml, 'wb') as f:
            # xml as UTF-8 encoded bytes
            document.write(f, pretty_print=True, encoding='utf-8')

        # set deployment descriptor ownership and permission
        os.chown(context_xml, self.uid, self.gid)
        os.chmod(context_xml, DEFAULT_FILE_MODE)

        if not wait:
            return

        logger.info('Waiting for web application to start')

        if webapp_id == 'ROOT':
            path = '/'
        else:
            path = '/' + webapp_id

        start_time = datetime.datetime.today()
        started = False
        counter = 0

        while not started:
            try:
                time.sleep(1)
                started = self.is_available(path, timeout=timeout)

            except requests.exceptions.SSLError as e:
                max_retry_error = e.args[0]
                reason = getattr(max_retry_error, 'reason')
                raise Exception('Server unreachable due to SSL error: %s' % reason) from e

            except pki.RETRYABLE_EXCEPTIONS as e:

                stop_time = datetime.datetime.today()
                counter = (stop_time - start_time).total_seconds()

                if max_wait is not None and counter >= max_wait:
                    raise Exception('Web application did not start after %ds' %
                                    max_wait) from e

                logger.info(
                    'Waiting for web application to start (%ds)',
                    int(round(counter)))

        logger.info('Web application started')

    def undeploy_webapp(
            self,
            webapp_id,
            force=False,
            wait=False,
            max_wait=60,
            timeout=None):

        context_xml = os.path.join(
            self.conf_dir,
            'Catalina',
            'localhost',
            webapp_id + '.xml')

        logger.info('Removing %s', context_xml)
        pki.util.remove(context_xml, force=force)

        if not wait:
            return

        logger.info('Waiting for web application to stop')

        if webapp_id == 'ROOT':
            path = '/'
        else:
            path = '/' + webapp_id

        start_time = datetime.datetime.today()
        stopped = False
        counter = 0

        while not stopped:
            try:
                time.sleep(1)
                stopped = not self.is_available(path, timeout=timeout)

            except requests.exceptions.SSLError as e:
                max_retry_error = e.args[0]
                reason = getattr(max_retry_error, 'reason')
                raise Exception('Server unreachable due to SSL error: %s' % reason) from e

            except pki.RETRYABLE_EXCEPTIONS as e:

                stop_time = datetime.datetime.today()
                counter = (stop_time - start_time).total_seconds()

                if max_wait is not None and counter >= max_wait:
                    raise Exception('Web application did not stop after %ds' %
                                    max_wait) from e

                logger.info(
                    'Waiting for web application to stop (%ds)',
                    int(round(counter)))

        logger.info('Web application stopped')

    def remove(self, force=False):

        logger.info('Removing %s', self.service_conf)
        pki.util.remove(self.service_conf, force=force)

        logger.info('Removing %s', self.log_dir)
        pki.util.rmtree(self.log_dir, force=force)

        logger.info('Removing %s', self.work_dir)
        pki.util.rmtree(self.work_dir, force=force)

        logger.info('Removing %s', self.webapps_dir)
        pki.util.rmtree(self.webapps_dir, force=force)

        logger.info('Removing %s', self.temp_dir)
        pki.util.rmtree(self.temp_dir, force=force)

        self.remove_libs(force=force)
        self.remove_conf_dir(force=force)

        logger.info('Removing %s', self.bin_dir)
        pki.util.unlink(self.bin_dir, force=force)

        logger.info('Removing %s', self.base_dir)
        pki.util.rmtree(self.base_dir, force=force)

    def remove_libs(self, force=False):

        logger.info('Removing %s', self.common_lib_dir)
        pki.util.unlink(self.common_lib_dir, force=force)

        logger.info('Removing %s', self.common_dir)
        pki.util.rmtree(self.common_dir, force=force)

        logger.info('Removing %s', self.lib_dir)
        pki.util.unlink(self.lib_dir, force=force)

    def remove_conf_dir(self, force=False):

        logger.info('Removing %s', self.conf_dir)
        pki.util.rmtree(self.conf_dir, force=force)

    def remove_nssdb(self, force=False):

        logger.info('Removing %s', self.nssdb_link)
        pki.util.unlink(self.nssdb_link, force=force)

        logger.info('Removing %s', self.nssdb_dir)
        pki.util.rmtree(self.nssdb_dir, force=force)

    def load(self):

        logger.info('Loading instance: %s', self.name)

        self.load_config()
        self.load_passwords()
        self.load_subsystems()

    def load_config(self):

        self.config.clear()

        logger.info('Loading global Tomcat config: %s', Tomcat.TOMCAT_CONF)
        pki.util.load_properties(Tomcat.TOMCAT_CONF, self.config)

        logger.info('Loading PKI Tomcat config: %s', PKIServer.TOMCAT_CONF)
        pki.util.load_properties(PKIServer.TOMCAT_CONF, self.config)

        if os.path.exists(self.tomcat_conf):
            logger.info('Loading instance Tomcat config: %s', self.tomcat_conf)
            pki.util.load_properties(self.tomcat_conf, self.config)

        # strip quotes
        for name, value in self.config.items():
            if value.startswith('"') and value.endswith('"'):
                self.config[name] = value[1:-1]

        self.config['NAME'] = self.name

    def load_passwords(self):

        self.passwords.clear()

        if os.path.exists(self.password_conf):
            logger.info('Loading password config: %s', self.password_conf)
            pki.util.load_properties(self.password_conf, self.passwords)

    def store_passwords(self):
        self.store_properties(self.password_conf, self.passwords)

    def load_subsystems(self):

        for subsystem_name in SUBSYSTEM_TYPES:

            subsystem_dir = os.path.join(self.base_dir, subsystem_name)
            if not os.path.exists(subsystem_dir):
                # Directory does not exist
                continue

            if not os.listdir(subsystem_dir):
                # Directory exists but it is empty
                continue

            subsystem = pki.server.subsystem.PKISubsystemFactory.create(self, subsystem_name)
            subsystem.load()
            self.subsystems[subsystem_name] = subsystem

    def get_subsystems(self):
        return list(self.subsystems.values())

    def get_subsystem(self, subsystem_name):
        return self.subsystems.get(subsystem_name)

    def enable_subsystems(self):
        for subsystem in self.get_subsystems():
            if not subsystem.is_enabled():
                subsystem.enable()

    def load_jss_config(self):

        jss_config = {}

        if os.path.exists(self.jss_conf):
            logger.info('Loading JSS config: %s', self.jss_conf)
            pki.util.load_properties(self.jss_conf, jss_config)

        return jss_config

    def store_jss_config(self, jss_config):
        self.store_properties(self.jss_conf, jss_config)

    def get_server_config(self):
        server_config = ServerConfig(self.server_xml)
        server_config.load()
        return server_config

    def get_password(self, name):

        # find password (e.g. internaldb, replicationdb) in password.conf
        if name in self.passwords:
            return self.passwords[name]

        # find password in keyring
        try:
            keyring = Keyring()
            key_name = self.name + '/' + name
            password = keyring.get_password(key_name=key_name)
            self.passwords[name] = password
            return password

        except subprocess.CalledProcessError:
            logger.info('Password unavailable in Keyring.')

        # prompt for password if not found
        password = getpass.getpass(prompt='Enter password for %s: ' % name)
        self.passwords[name] = password

        return password

    def get_token_password(self, token=pki.nssdb.INTERNAL_TOKEN_NAME):

        # determine the password name for the token
        if not pki.nssdb.normalize_token(token):
            name = pki.nssdb.INTERNAL_TOKEN_NAME

        else:
            name = 'hardware-%s' % token

        # find password in password.conf
        if name in self.passwords:
            return self.passwords[name]

        # find password in keyring
        try:
            keyring = Keyring()
            key_name = self.name + '/' + name
            password = keyring.get_password(key_name=key_name)
            self.passwords[name] = password
            return password

        except subprocess.CalledProcessError:
            logger.info('Password unavailable in Keyring.')

        # prompt for password if not found
        password = getpass.getpass(prompt='Enter password for %s: ' % token)
        self.passwords[name] = password

        return password

    def get_sslserver_cert_nickname(self):

        # Load SSL server cert nickname from server.xml

        server_config = self.get_server_config()
        connector = server_config.get_connector('Secure')

        if connector is None:
            return None

        sslhost = server_config.get_sslhost(connector)
        sslcert = server_config.get_sslcert(sslhost)

        return sslcert.get('certificateKeyAlias')

    def set_sslserver_cert_nickname(self, nickname, token=None):

        # Store SSL server cert nickname into server.xml

        if pki.nssdb.normalize_token(token):
            fullname = token + ':' + nickname
        else:
            fullname = nickname

        server_config = self.get_server_config()
        connector = server_config.get_connector('Secure')

        if connector is None:
            raise KeyError('Connector not found: Secure')

        sslhost = server_config.get_sslhost(connector)
        sslcert = server_config.get_sslcert(sslhost)
        sslcert.set('certificateKeyAlias', fullname)
        server_config.save()

    @staticmethod
    def split_cert_id(cert_id):
        """
        Utility method to return cert_tag and corresponding subsystem details from cert_id

        :param cert_id: Cert ID
        :type cert_id: str
        :returns: (subsystem_name, cert_tag)
        :rtype: (str, str)
        """
        if cert_id == 'sslserver' or cert_id == 'subsystem':
            subsystem_name = None
            cert_tag = cert_id
        else:
            parts = cert_id.split('_', 1)
            subsystem_name = parts[0]
            cert_tag = parts[1]
        return subsystem_name, cert_tag

    @staticmethod
    def build_ca_files(client_nssdb):
        if not client_nssdb:
            return None

        ca_cert = os.path.join(client_nssdb, "ca.crt")
        if os.path.exists(ca_cert):
            return ca_cert

        return None

    @staticmethod
    def setup_password_authentication(username, password, subsystem_name='ca', secure_port='8443',
                                      client_nssdb=None):
        """
        Return a PKIConnection, logged in using username and password.
        """
        ca_cert = PKIServer.build_ca_files(client_nssdb)
        connection = pki.client.PKIConnection('https', socket.getfqdn(), secure_port,
                                              cert_paths=ca_cert)
        connection.authenticate(username, password)
        account_client = pki.account.AccountClient(connection, subsystem=subsystem_name)
        account_client.login()
        return connection

    @staticmethod
    def setup_cert_authentication(
            client_nssdb_pass, client_nssdb_pass_file, client_cert,
            client_nssdb, tmpdir, subsystem_name=None, secure_port='8443'):
        """
        Utility method to set up a secure authenticated connection with a
        subsystem of PKI Server through PKI client

        :param client_nssdb_pass: Client NSS db plain password
        :type client_nssdb_pass: str
        :param client_nssdb_pass_file: File containing client NSS db password
        :type client_nssdb_pass_file: str
        :param client_cert: Client Cert nick name
        :type client_cert: str
        :param client_nssdb: Client NSS db path
        :type client_nssdb: str
        :param tmpdir: Absolute path of temp dir to store p12 and pem files
        :type tmpdir: str
        :param subsystem_name: Name of the subsystem
           DEPRECATED: https://github.com/dogtagpki/pki/wiki/PKI-10.8-Python-Changes
        :type subsystem_name: str
        :param secure_port: Secure Port Number
        :type secure_port: str
        :return: Authenticated secure connection to PKI server
        """

        if subsystem_name is not None:
            logger.warning(
                '%s:%s: The subsystem_name in PKIServer.setup_cert_authentication() has '
                'been deprecated (https://github.com/dogtagpki/pki/wiki/PKI-10.8-Python-Changes).',
                inspect.stack()[1].filename, inspect.stack()[1].lineno)

        temp_auth_p12 = os.path.join(tmpdir, 'auth.p12')
        temp_auth_cert = os.path.join(tmpdir, 'auth.pem')

        if not client_cert:
            raise PKIServerException('Client cert nickname is required.')

        # Create a p12 file using
        # pk12util -o <p12 file name> -n <cert nick name> -d <NSS db path>
        # -W <pkcs12 password> -K <NSS db pass>
        cmd_generate_pk12 = [
            'pk12util',
            '-o', temp_auth_p12,
            '-n', client_cert,
            '-d', client_nssdb
        ]

        # The pem file used for authentication. Created from a p12 file using the
        # command:
        # openssl pkcs12 -in <p12_file_path> -out /tmp/auth.pem -nodes
        cmd_generate_pem = [
            'openssl',
            'pkcs12',
            '-in', temp_auth_p12,
            '-out', temp_auth_cert,
            '-nodes'
        ]

        # The PEM file containing the CA certificate. Created from a p12 file
        # using the command:
        # openssl pkcs12 -in <p12_file_path> -out /tmp/auth.pem -nodes -cacerts -nokeys
        cmd_generate_ca = [
            'openssl', 'pkcs12',
            '-in', temp_auth_p12,
            '-out', os.path.join(client_nssdb, "ca.crt"),
            '-nodes',
            '-cacerts',
            '-nokeys'
        ]

        if client_nssdb_pass_file:
            # Use the same password file for the generated pk12 file
            cmd_generate_pk12.extend(['-k', client_nssdb_pass_file,
                                      '-w', client_nssdb_pass_file])
            cmd_generate_pem.extend(['-passin', 'file:' + client_nssdb_pass_file])
            cmd_generate_ca.extend(['-passin', 'file:' + client_nssdb_pass_file])
        else:
            # Use the same password for the generated pk12 file
            cmd_generate_pk12.extend(['-K', client_nssdb_pass,
                                      '-W', client_nssdb_pass])
            cmd_generate_pem.extend(['-passin', 'pass:' + client_nssdb_pass])
            cmd_generate_ca.extend(['-passin', 'pass:' + client_nssdb_pass])

        # Generate temp_auth_p12 file
        res_pk12 = subprocess.check_output(cmd_generate_pk12,
                                           stderr=subprocess.STDOUT).decode('utf-8')
        logger.debug('Result of pk12 generation: %s', res_pk12)

        # Use temp_auth_p12 generated in previous step to
        # to generate temp_auth_cert PEM file
        res_pem = subprocess.check_output(cmd_generate_pem,
                                          stderr=subprocess.STDOUT).decode('utf-8')
        logger.debug('Result of pem generation: %s', res_pem)

        # When we generate the .p12 file, we can extract the ca certificate.
        # We remove it when it already exists. This ensures we always have
        # an up-to-date CA certificate.
        ca_cert = PKIServer.build_ca_files(client_nssdb)
        if ca_cert and os.path.exists(ca_cert):
            os.remove(ca_cert)

        # Export the CA each time. This ensures it is always up to date when
        # trying to connect.
        res_ca = subprocess.check_output(cmd_generate_ca,
                                         stderr=subprocess.STDOUT).decode('utf-8')
        logger.debug('Result of CA generation: %s', res_ca)
        ca_cert = PKIServer.build_ca_files(client_nssdb)

        # Create a PKIConnection object that stores the details of subsystem.
        connection = pki.client.PKIConnection('https', socket.getfqdn(), secure_port,
                                              subsystem_name, cert_paths=ca_cert)

        # Bind the authentication with the connection object
        connection.set_authentication_cert(temp_auth_cert)

        return connection

    @staticmethod
    def renew_certificate(connection, output, serial):
        """
        Renew cert associated with the provided serial

        :param connection: Secure authenticated connection to PKI Server
        :type connection: PKIConnection
        :param output: Location of the new cert file to be written to
        :type output: str
        :param serial: Serial number of the cert to be renewed
        :type serial: str
        :return: None
        :rtype: None
        """

        # Instantiate the CertClient
        cert_client = pki.cert.CertClient(connection)

        inputs = dict()
        inputs['serial_num'] = serial

        # request: CertRequestInfo object for request generated.
        # cert: CertData object for certificate generated (if any)
        ret = cert_client.enroll_cert(inputs=inputs, profile_id='caManualRenewal')

        request_data = ret[0].request
        cert_data = ret[0].cert

        logger.info('Request ID: %s', request_data.request_id)
        logger.info('Request Status: %s', request_data.request_status)
        logger.debug('request_data: %s', request_data)
        logger.debug('cert_data: %s', cert_data)

        if not cert_data:
            raise PKIServerException('Unable to renew system '
                                     'certificate for serial: %s' % serial)

        # store cert_id for usage later
        cert_serial_number = cert_data.serial_number
        if not cert_serial_number:
            raise PKIServerException('Unable to retrieve serial number of '
                                     'renewed certificate.')

        logger.info('Serial Number: %s', cert_serial_number)
        logger.info('Issuer: %s', cert_data.issuer_dn)
        logger.info('Subject: %s', cert_data.subject_dn)
        logger.debug('Pretty Print:')
        logger.debug(cert_data.pretty_repr)

        new_cert_data = cert_client.get_cert(cert_serial_number=cert_serial_number)
        with open(output, 'w', encoding='utf-8') as f:
            f.write(new_cert_data.encoded)

    @staticmethod
    def load_audit_events(filename):
        '''
        This method loads audit event info from audit-events.properties
        and return it as a map of objects.
        '''

        logger.info('Loading %s', filename)

        with open(filename, encoding='utf-8') as f:
            lines = f.read().splitlines()

        events = {}

        event_pattern = re.compile(r'# Event: (\S+)')
        subsystems_pattern = re.compile(r'# Applicable subsystems: (.*)')
        enabled_pattern = re.compile(r'# Enabled by default: (.*)')

        event = None

        for line in lines:

            logger.debug('Parsing: %s', line)

            event_match = event_pattern.match(line)
            if event_match:

                name = event_match.group(1)
                logger.info('Found event %s', name)

                event = {}
                event['name'] = name
                event['subsystems'] = []
                event['enabled_by_default'] = False

                events[name] = event
                continue

            subsystems_match = subsystems_pattern.match(line)
            if subsystems_match:

                subsystems = subsystems_match.group(1)
                logger.info('Found subsystems %s', subsystems)

                subsystems = subsystems.replace(' ', '').split(',')
                event['subsystems'] = subsystems

            enabled_match = enabled_pattern.match(line)
            if enabled_match:

                enabled = enabled_match.group(1)
                logger.info('Found enabled by default %s', enabled)

                if enabled == 'Yes':
                    event['enabled_by_default'] = True
                else:
                    event['enabled_by_default'] = False

        logger.info('Events:')

        for name, event in events.items():
            logger.info('- %s', name)
            logger.info('  Applicable subsystems: %s', event['subsystems'])
            logger.info('  Enabled by default: %s', event['enabled_by_default'])

        return events


class ExternalCert(object):

    def __init__(self, nickname=None, token=None):
        self.nickname = nickname
        self.token = token


class ServerConfig(object):

    def __init__(self, filename):
        self.filename = filename
        self.document = etree.ElementTree()

    def load(self):
        self.document = etree.parse(self.filename, parser)

    def save(self):
        with open(self.filename, 'wb') as f:
            self.document.write(f, pretty_print=True, encoding='utf-8')

    def get_port(self):
        server = self.document.getroot()
        return server.get('port')

    def get_unsecure_port(self):

        for connector in self.get_connectors():

            sslEnabled = connector.get('SSLEnabled')
            protocol = connector.get('protocol')

            if not sslEnabled and not protocol.startswith('AJP/'):
                return connector.get('port')

        return None

    def get_secure_port(self):

        for connector in self.get_connectors():

            sslEnabled = connector.get('SSLEnabled')

            if sslEnabled:
                return connector.get('port')

        return None

    def get_ajp_port(self):

        for connector in self.get_connectors():

            protocol = connector.get('protocol')
            if protocol.startswith('AJP/'):
                return connector.get('port')

        return None

    def get_listeners(self):
        server = self.document.getroot()
        return server.findall('Listener')

    def get_listener(self, className):

        listeners = self.get_listeners()

        for listener in listeners:
            c = listener.get('className')
            if c == className:
                return listener

        raise KeyError('Listener not found: %s' % className)

    def create_listener(self, className):

        listener = etree.Element('Listener')
        listener.set('className', className)

        listeners = self.get_listeners()
        last_listener = listeners[-1]

        server = self.document.getroot()
        index = server.index(last_listener) + 1
        server.insert(index, listener)

        return listener

    def remove_listener(self, className):

        listener = self.get_listener(className)
        server = listener.getparent()
        server.remove(listener)

    def get_connectors(self):

        server = self.document.getroot()

        names = set()
        connectors = []
        counter = 0

        service = server.find('Service[@name="Catalina"]')

        for connector in service.findall('Connector'):

            name = connector.get('name')

            if not name:  # connector has no name, generate a temporary name

                while True:  # find unused name
                    counter += 1
                    name = 'Connector%d' % counter
                    if name not in names:
                        break

                connector.set('name', name)

            names.add(name)
            connectors.append(connector)

        return connectors

    def get_connector(self, name):

        connectors = self.get_connectors()

        for connector in connectors:
            n = connector.get('name')
            if n == name:
                return connector

        return None

    def create_connector(self, name):

        connector = etree.Element('Connector')
        connector.set('name', name)

        server = self.document.getroot()

        service = server.find('Service[@name="Catalina"]')
        connectors = service.findall('Connector')
        last_connector = connectors[-1]

        index = service.index(last_connector) + 1
        service.insert(index, connector)

        return connector

    def remove_connector(self, name):

        connector = self.get_connector(name)

        if connector is None:
            raise KeyError('Connector not found: %s' % name)

        service = connector.getparent()
        service.remove(connector)

    def get_sslhosts(self, connector):
        return list(connector.iter('SSLHostConfig'))

    def get_sslhost(self, connector, hostname='_default_'):
        sslhosts = self.get_sslhosts(connector)

        for sslhost in sslhosts:
            h = sslhost.get('hostName', '_default_')
            if h == hostname:
                return sslhost

        raise KeyError('SSL host not found: %s' % hostname)

    def create_sslhost(self, connector, hostname='_default_'):

        sslhost = etree.Element('SSLHostConfig')
        if hostname != '_default_':
            sslhost.set('hostName', hostname)

        connector.append(sslhost)

        return sslhost

    def remove_sslhost(self, connector, hostname):

        sslhost = self.get_sslhost(connector, hostname)
        connector.remove(sslhost)

    def get_sslcerts(self, sslhost):
        return list(sslhost.iter('Certificate'))

    def get_sslcert(self, sslhost, certType='UNDEFINED'):
        sslcerts = self.get_sslcerts(sslhost)

        for sslcert in sslcerts:
            t = sslcert.get('type', 'UNDEFINED')
            if t == certType:
                return sslcert

        raise KeyError('SSL certificate not found: %s' % certType)

    def create_sslcert(self, sslhost, certType):

        sslcert = etree.Element('Certificate')
        if certType != 'UNDEFINED':
            sslcert.set('type', certType)

        sslhost.append(sslcert)

        return sslcert

    def remove_sslcert(self, sslhost, certType):

        sslcert = self.get_sslcert(sslhost, certType)
        sslhost.remove(sslcert)


class PKIDatabaseConnection(object):

    def __init__(self, url='ldap://localhost:389'):

        self.url = url

        self.nssdb_dir = None

        self.bind_dn = None
        self.bind_password = None

        self.client_cert_nickname = None
        self.nssdb_password = None

        self.temp_dir = None
        self.ldap = None

    def set_security_database(self, nssdb_dir=None):
        self.nssdb_dir = nssdb_dir

    def set_credentials(self, bind_dn=None, bind_password=None,
                        client_cert_nickname=None, nssdb_password=None):
        self.bind_dn = bind_dn
        self.bind_password = bind_password
        self.client_cert_nickname = client_cert_nickname
        self.nssdb_password = nssdb_password

    def open(self):

        self.temp_dir = tempfile.mkdtemp()

        if self.nssdb_dir:
            ldap.set_option(ldap.OPT_X_TLS_CACERTDIR, self.nssdb_dir)

        if self.client_cert_nickname:
            password_file = os.path.join(self.temp_dir, 'password.txt')
            with open(password_file, 'w', encoding='utf-8') as f:
                f.write(self.nssdb_password)

            ldap.set_option(ldap.OPT_X_TLS_CERTFILE, self.client_cert_nickname)
            ldap.set_option(ldap.OPT_X_TLS_KEYFILE, password_file)

        self.ldap = ldap.initialize(self.url)

        if self.bind_dn and self.bind_password:
            self.ldap.simple_bind_s(self.bind_dn, self.bind_password)

    def close(self):

        if self.ldap:
            self.ldap.unbind_s()

        if self.temp_dir:
            shutil.rmtree(self.temp_dir)


class PKIServerException(pki.PKIException):

    def __init__(self, message, exception=None,
                 instance=None, subsystem=None):
        pki.PKIException.__init__(self, message, exception)

        self.instance = instance
        self.subsystem = subsystem
