"""A small Walter CLI over PocketDeploy's infrastructure and recovery library."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import sys
import time

from pocketdeploy.common import DeployError, local_path
from pocketdeploy.state import deployment_lock
from pocketdeploy.gcp import GCP
from pocketdeploy.host import Host
from pocketdeploy.runtime import initialize, session, recorded_operation, delete_compute
from pocketdeploy.output import Reporter
from pocketdeploy import vault
from .config import load
from . import controller

COMMANDS = ('init', 'plan', 'converge', 'status', 'ssh', 'ssh-install', 'ssh-uninstall',
            'converge-nix', 'converge-asdf', 'delete', 'vault-save', 'vault-restore')


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise DeployError('Invalid arguments; run walter --help.', code='invalid_usage')


def parser():
    p = Parser(prog='walter', description='Google Cloud development machines using PocketDeploy.', allow_abbrev=False)
    p.add_argument('command', choices=COMMANDS)
    p.add_argument('-f', '--file', default='colors.yml')
    p.add_argument('--dry-run', action='store_true', help='Plan converge/delete without mutations')
    p.add_argument('--json', action='store_true')
    p.add_argument('--quiet', action='store_true')
    p.add_argument('--verbose', action='store_true')
    p.add_argument('--user', help='Primary login or configured seat, for ssh')
    p.add_argument('--ssh-command', help='Explicit remote command, for ssh')
    p.add_argument('--document', help='Exact Vault state document to restore')
    p.add_argument('--version', help='Exact Vault state version to restore')
    p.add_argument('--destination', help='Restore directory with matching colors.yml')
    p.add_argument('--overwrite', action='store_true')
    return p


def execute(args, reporter):
    if args.verbose and args.quiet:
        raise DeployError('--verbose and --quiet conflict.', code='invalid_usage')
    if args.dry_run and args.command not in ('plan', 'converge', 'delete'):
        raise DeployError('--dry-run requires plan, converge or delete.', code='invalid_usage')
    if (args.user or args.ssh_command) and args.command != 'ssh':
        raise DeployError('SSH options require ssh.', code='invalid_usage')
    if args.json and args.command == 'ssh':
        raise DeployError('Interactive SSH does not support --json.', code='invalid_usage')
    if (args.document or args.version or args.destination or args.overwrite) and args.command != 'vault-restore':
        raise DeployError('Restore options require vault-restore.', code='invalid_usage')
    config, deployment = load(args.file)
    root = Path(deployment['_root'])
    if args.command == 'vault-restore':
        destination = Path(args.destination).absolute() if args.destination else root
        destination.mkdir(parents=True, exist_ok=True, mode=0o700)
        with deployment_lock(local_path(destination, deployment['state-file'])):
            return vault.restore(deployment, destination, args.document, args.version, args.overwrite)
    if args.command == 'ssh-uninstall':
        # No provider access or identity generation is needed to remove owned aliases.
        return {'profile': config['profile'], 'aliases_removed': controller.aliases(config, deployment, Host(deployment, None, root), remove=True)}
    read_only = args.command in ('plan', 'status') or args.dry_run
    with session(deployment, read_only=read_only,
                 allow_create=args.command in ('init', 'plan', 'converge'), provider_factory=GCP) as context:
        state, cloud, host = context.state, context.cloud, context.host
        if args.command == 'init':
            return initialize(deployment, state, host, root)
        if args.command == 'vault-save':
            return vault.save(deployment, state, root)
        if args.command == 'status':
            observed = cloud.inspect()
            result = {'profile': config['profile'], 'state': state.safe_status(), 'resources': observed}
            if observed.get('compute'):
                result['provisioning'] = controller.remote(host, cloud.connection(), config, 'status')
            return result
        if read_only:
            if args.command == 'delete':
                return {'profile': config['profile'], 'actions': cloud.plan_delete(), 'protected': config['compute-prevent-destroy']}
            return {'profile': config['profile'], 'actions': cloud.plan(),
                    'provisioning': {'action': 'reconcile', 'users': [deployment.get('ssh-user', 'ubuntu'), *config['users']]}}
        if args.command == 'ssh':
            connection = cloud.connection()
            if args.user:
                if args.user not in [deployment.get('ssh-user', 'ubuntu'), *config['users']]:
                    raise DeployError('SSH user is not a configured login.')
                connection['user'] = args.user
            return {'ssh_exit': host.ssh(connection, args.ssh_command)}
        if args.command == 'ssh-install':
            connection = cloud.connection()
            host.trusted_public(connection)
            # Refresh the pinned IP entry before installing aliases.
            host.run_python(connection, 'import json; print(json.dumps({"ok":True}))', {})
            return {'profile': config['profile'], 'aliases_changed': controller.aliases(config, deployment, host, connection)}
        with recorded_operation(state, args.command, deployment['_desired_hash']) as operation:
            if args.command == 'converge':
                result = asyncio.run(controller.converge(config, deployment, state, cloud, host, operation, reporter))
            elif args.command in ('converge-nix', 'converge-asdf'):
                result = controller.complete_remote(state, operation, {**deployment, '_walter': config}, host, cloud.connection(), args.command)
            elif args.command == 'delete':
                result = delete_compute(deployment, state, cloud, host, operation,
                                        before_cleanup=lambda: controller.aliases(config, deployment, host, remove=True))
                result = {'profile': config['profile'], 'deleted': result}
            else:
                raise DeployError('Unsupported command.')
            return result


def main(argv=None):
    os.umask(0o077)
    argv = sys.argv[1:] if argv is None else argv
    if not argv:
        parser().print_help()
        return 0
    reporter = Reporter(json_mode='--json' in argv, quiet='--quiet' in argv)
    started = time.monotonic()
    try:
        args = parser().parse_args(argv)
        reporter.command, reporter.verbose = args.command, args.verbose
        with reporter.activate():
            result = execute(args, reporter)
        if args.command == 'ssh':
            return result['ssh_exit'] if result['ssh_exit'] >= 0 else 128 - result['ssh_exit']
        if args.json:
            reporter.success(result, elapsed_seconds=time.monotonic() - started)
        else:
            print(json.dumps(result, indent=2))
        return 0
    except DeployError as error:
        reporter.failure(str(error), code=error.code, stage=error.stage or reporter.failed_stage,
                         elapsed_seconds=time.monotonic() - started, diagnostic=error.diagnostic)
        return 2 if error.code == 'invalid_usage' else 1
    except KeyboardInterrupt:
        reporter.failure('Interrupted; retry to reconcile recorded operations.', code='interrupted')
        return 130
    except Exception:
        reporter.failure('Walter operation failed; private output suppressed.')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
