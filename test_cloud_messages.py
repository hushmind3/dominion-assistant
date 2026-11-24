import unittest
from cloud_link import CloudLink
class NoticeTests(unittest.TestCase):
    def test_task_notices_are_bounded_and_idempotent(self):
        link=CloudLink();link.publish_task('x','no device');self.assertFalse(link.outbox)
        link.config={'token':'device'}
        link.publish_task('x','done');link.publish_task('x','again')
        self.assertEqual(link.outbox,{'task:x':{'id':'task:x','text':'done'}})
        self.assertTrue(link.notices.is_set())
