"""Minimal Windows shim for the Unix-only ``pwd`` module.

SearXNG 的 searx/valkeydb.py 在模块顶层就 ``import pwd``，Windows 上没有这个模块，
整个 searx.webapp 会因为 ModuleNotFoundError 起不来。这里只补本机跑起来用得上的最小接口。
"""

import os


class struct_passwd(tuple):
    _fields = ("pw_name", "pw_passwd", "pw_uid", "pw_gid", "pw_gecos", "pw_dir", "pw_shell")

    def __new__(
        cls,
        pw_name="Windows",
        pw_passwd="x",
        pw_uid=0,
        pw_gid=0,
        pw_gecos="",
        pw_dir="",
        pw_shell="",
    ):
        return super().__new__(cls, (pw_name, pw_passwd, pw_uid, pw_gid, pw_gecos, pw_dir, pw_shell))

    def __getattr__(self, name):
        try:
            return self[self._fields.index(name)]
        except ValueError as exc:
            raise AttributeError(name) from exc


def getpwuid(uid):
    return struct_passwd(pw_uid=uid)


def getpwnam(name):
    return struct_passwd(pw_name=name)


if not hasattr(os, "getuid"):
    os.getuid = lambda: 0
    os.geteuid = lambda: 0
