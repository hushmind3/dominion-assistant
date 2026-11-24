import base64
import tempfile
import unittest
from pathlib import Path
from attachments import AttachmentStore, Vision
from app import Conversation

def payload(name,raw,mime='text/plain',focus='summarize'):
    return dict(name=name,mime=mime,base64=base64.b64encode(raw).decode(),focus=focus)

class AttachmentTests(unittest.TestCase):
    def test_document_is_real_host_context_and_survives_restart(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'state.json'
            c=Conversation(path)
            a=c.attachments.add(payload('note.txt',b'The appointment is 4 PM.'))
            c=Conversation(path)
            self.assertEqual(c.attachments.bytes(a['id'])[1],b'The appointment is 4 PM.')
            c.mobile_submit({'clientId':'one','text':'When?','kind':'chat','attachmentId':a['id']})
            self.assertIn('The appointment is 4 PM.',c.v2_request()['instruction'])
            self.assertEqual(c.mobile_history()['turns'][0]['attachment']['id'],a['id'])
    def test_no_fake_photo_analysis(self):
        s=AttachmentStore()
        s.vision.clear()
        with self.assertRaises(ValueError): s.add(payload('p.jpg',b'\xff\xd8\xffabc','image/jpeg'))
        with self.assertRaises(ValueError): s.add(payload('p.jpg',b'not a photo','image/jpeg'))
    def test_duplicate_and_invalid_name(self):
        s=AttachmentStore()
        p=payload('note.txt',b'abc')
        self.assertEqual(s.add(p),s.add(p))
        with self.assertRaises(ValueError): s.add(payload('../x.txt',b'abc'))
    def test_pdf_extract(self):
        from pypdf import PdfWriter
        import io
        w=PdfWriter();w.add_blank_page(100,100)
        b=io.BytesIO();w.write(b)
        with self.assertRaises(ValueError): AttachmentStore().add(payload('blank.pdf',b.getvalue(),'application/pdf'))
    def test_vision_adapter_uses_real_returned_evidence(self):
        class FakeVision:
            def analyze(self,raw,mime,focus):
                self.input=(raw,mime,focus)
                return 'A red bicycle.'
        v=FakeVision();s=AttachmentStore(vision=v)
        a=s.add(payload('p.jpg',b'\xff\xd8\xffabc','image/jpeg','What color?'))
        self.assertEqual(v.input[2],'What color?')
        self.assertEqual(s.get(a['id'])['excerpt'],'A red bicycle.')
