"""Synthetic HTTP responses only; no network, real sources, or media files."""

import io
import unittest

from http_validation import MediaProbeError, PROBE_BYTES, read_media_probe, validate_media_probe


def mp4_header(size=PROBE_BYTES):
    ftyp = b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isommp42"
    return ftyp.ljust(size, b"\x00")


def webm_header(size=PROBE_BYTES):
    return b"\x1a\x45\xdf\xa3\x87\x42\x82\x84webm".ljust(size, b"\x00")


class MediaProbeTests(unittest.TestCase):
    def test_valid_mp4_partial_response(self):
        summary = validate_media_probe(206, {
            "Content-Type": "video/mp4", "Content-Length": "1024",
            "Content-Range": "bytes 0-1023/50000",
        }, mp4_header())
        self.assertIn("ISO-BMFF", summary)
        self.assertIn("verified bytes 0-1023", summary)

    def test_valid_webm_partial_response(self):
        summary = validate_media_probe(206, {
            "Content-Type": "video/webm", "Content-Range": "bytes 0-1023/*",
        }, webm_header())
        self.assertIn("WebM/EBML", summary)

    def test_range_ignored_is_reported_as_http_200(self):
        summary = validate_media_probe(200, {
            "Content-Type": "video/mp4", "Content-Length": "50000",
        }, mp4_header())
        self.assertIn("server ignored Range", summary)
        self.assertNotIn("verified bytes", summary)

    def test_missing_content_range_does_not_claim_range_verified(self):
        summary = validate_media_probe(206, {"Content-Type": "video/mp4"}, mp4_header())
        self.assertIn("range unverified", summary)

    def test_generic_binary_content_type_requires_signature(self):
        for content_type in ("application/octet-stream", "binary/octet-stream"):
            with self.subTest(content_type=content_type):
                self.assertIn("ISO-BMFF", validate_media_probe(200, {"Content-Type": content_type}, mp4_header()))
                with self.assertRaisesRegex(MediaProbeError, "signature"):
                    validate_media_probe(200, {"Content-Type": content_type}, b"Error".ljust(1024, b" "))

    def test_header_names_and_media_type_are_case_insensitive(self):
        summary = validate_media_probe(200, {"content-type": "Video/MP4; charset=binary"}, mp4_header())
        self.assertIn("ISO-BMFF", summary)

    def test_rejects_html_json_and_text_mime_types_even_with_media_bytes(self):
        for content_type in ("text/html", "application/json", "text/plain", "image/jpeg", ""):
            with self.subTest(content_type=content_type):
                with self.assertRaisesRegex(MediaProbeError, "Content-Type"):
                    validate_media_probe(200, {"Content-Type": content_type}, mp4_header())

    def test_rejects_error_bodies_mislabeled_as_video(self):
        for error_body in (b"<html>Access denied</html>", b'{"error":"unavailable"}', b"Service unavailable"):
            with self.subTest(error_body=error_body):
                with self.assertRaisesRegex(MediaProbeError, "signature"):
                    validate_media_probe(200, {"Content-Type": "video/mp4"}, error_body.ljust(1024, b" "))

    def test_rejects_truncated_200_body_against_content_length(self):
        with self.assertRaisesRegex(MediaProbeError, "truncated"):
            validate_media_probe(200, {"Content-Type": "video/mp4", "Content-Length": "50000"}, mp4_header(800))

    def test_rejects_short_200_body_without_declared_length(self):
        with self.assertRaisesRegex(MediaProbeError, "truncated"):
            validate_media_probe(200, {"Content-Type": "video/mp4"}, mp4_header(800))

    def test_accepts_declared_complete_small_body(self):
        summary = validate_media_probe(200, {
            "Content-Type": "video/mp4", "Content-Length": "800",
        }, mp4_header(800))
        self.assertIn("server ignored Range", summary)

    def test_accepts_partial_range_limited_by_total_file_size(self):
        summary = validate_media_probe(206, {
            "Content-Type": "video/mp4", "Content-Length": "800", "Content-Range": "bytes 0-799/800",
        }, mp4_header(800))
        self.assertIn("verified bytes 0-799", summary)

    def test_rejects_truncated_206_body(self):
        with self.assertRaisesRegex(MediaProbeError, "truncated"):
            validate_media_probe(206, {
                "Content-Type": "video/mp4", "Content-Range": "bytes 0-1023/50000",
            }, mp4_header(800))

    def test_rejects_invalid_or_wrong_content_range(self):
        for content_range in (
            "bytes 1-1024/50000", "bytes 0-2047/50000", "bytes 0-1023/1000",
            "bytes 0-799/50000", "bytes 0-1023/1023", "bytes */50000", "invalid",
        ):
            with self.subTest(content_range=content_range):
                with self.assertRaisesRegex(MediaProbeError, "Content-Range"):
                    validate_media_probe(206, {"Content-Type": "video/mp4", "Content-Range": content_range}, mp4_header())

    def test_rejects_disagreement_between_length_and_range(self):
        with self.assertRaisesRegex(MediaProbeError, "disagrees"):
            validate_media_probe(206, {
                "Content-Type": "video/mp4", "Content-Length": "800", "Content-Range": "bytes 0-1023/50000",
            }, mp4_header())

    def test_rejects_invalid_content_length(self):
        for content_length in ("-1", "invalid", "1024, 2048"):
            with self.subTest(content_length=content_length):
                with self.assertRaisesRegex(MediaProbeError, "Content-Length"):
                    validate_media_probe(200, {"Content-Type": "video/mp4", "Content-Length": content_length}, mp4_header())

    def test_rejects_mime_and_signature_disagreement(self):
        for content_type, body in (("video/mp4", webm_header()), ("video/webm", mp4_header())):
            with self.subTest(content_type=content_type):
                with self.assertRaisesRegex(MediaProbeError, "disagrees"):
                    validate_media_probe(200, {"Content-Type": content_type}, body)

    def test_rejects_incomplete_file_type_box(self):
        body = (4096).to_bytes(4, "big") + mp4_header()[4:]
        with self.assertRaisesRegex(MediaProbeError, "signature"):
            validate_media_probe(200, {"Content-Type": "video/mp4"}, body)

    def test_rejects_bad_status_and_encoded_or_out_of_bounds_samples(self):
        with self.assertRaisesRegex(MediaProbeError, "HTTP 503"):
            validate_media_probe(503, {"Content-Type": "video/mp4"}, mp4_header())
        with self.assertRaisesRegex(MediaProbeError, "uncompressed"):
            validate_media_probe(200, {"Content-Type": "video/mp4", "Content-Encoding": "gzip"}, mp4_header())
        for size in (200, 2048):
            with self.subTest(size=size):
                with self.assertRaisesRegex(MediaProbeError, "must contain"):
                    validate_media_probe(200, {"Content-Type": "video/mp4"}, mp4_header(size))

    def test_ignored_range_response_read_is_bounded(self):
        response = io.BytesIO(mp4_header() + b"\x00" * 10000)
        response.status = 200
        response.headers = {"Content-Type": "video/mp4", "Content-Length": "11024"}
        body, summary = read_media_probe(response)
        self.assertEqual(len(body), PROBE_BYTES)
        self.assertEqual(response.tell(), PROBE_BYTES)
        self.assertIn("server ignored Range", summary)


if __name__ == "__main__":
    unittest.main()
