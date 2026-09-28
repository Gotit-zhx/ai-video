"""OS-owned locks: released by the OS after a crash; no stale lock deletion."""
import os

if os.name == "nt":
    import msvcrt
    LOCK_EX, LOCK_NB = 1, 2

    def flock(fd, _flags):
        os.lseek(fd, 0, os.SEEK_SET)
        if os.fstat(fd).st_size == 0:
            os.write(fd, b"\0")
            os.lseek(fd, 0, os.SEEK_SET)
        try:
            msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
        except OSError as error:
            raise BlockingIOError("Account is busy") from error
else:
    from fcntl import flock, LOCK_EX, LOCK_NB
