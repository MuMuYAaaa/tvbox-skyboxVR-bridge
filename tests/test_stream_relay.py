"""Real HTTP integration checks for the production StreamRelay Java class.

Only the upstream media server is a fixture. The relay, HTTP sockets, redirects,
URL resolution, cookie handling, and byte transfers use the actual JDK code.
Run with a local Python 3 and JDK 17 or 18; no Android runtime or dependencies needed.
"""
from __future__ import annotations

import base64
import gzip
import http.client
import re
import subprocess
import os
import shutil
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "build" / "relay-integration"
JAVA = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java") if os.environ.get("JAVA_HOME") else Path(shutil.which("java") or "java")
JAVAC = Path(os.environ["JAVA_HOME"]) / "bin" / ("javac.exe" if os.name == "nt" else "javac") if os.environ.get("JAVA_HOME") else Path(shutil.which("javac") or "javac")
REFERER = "https://fixture.invalid/watch?secret=referer_secret"
COOKIE = "fixture_auth=cookie_secret"
AGENT = "TVBoxRelayIntegration/1.0"
SIGNED_QUERY = "token=a%2Bb%3D&part=one%2Ftwo&empty=&dup=1&dup=2"
MEDIA = bytes(range(256)) * 32
KEY = b"0123456789abcdef"
INIT = b"fixture-init-mp4"
SEGMENT = b"G" + bytes(range(187))

HARNESS = r'''import com.github.tvbox.osc.skybox.StreamRelay;
import java.io.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
public final class RelayIntegrationHarness {
    public static void main(String[] args) throws Exception {
        Map<String,String> headers = new LinkedHashMap<>();
        headers.put("Referer", "https://fixture.invalid/watch?secret=referer_secret");
        headers.put("Cookie", "fixture_auth=cookie_secret");
        headers.put("User-Agent", "TVBoxRelayIntegration/1.0");
        headers.put("Host", "must-not-use.invalid");
        headers.put("Accept-Encoding", "gzip");
        headers.put("Range", "bytes=999-1000");
        try (StreamRelay relay = new StreamRelay(args[0], headers)) {
            System.out.println(relay.mediaUrl());
            System.out.flush();
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            for (String line; (line = input.readLine()) != null;) {
                if (line.equals("CLOSE")) break;
                if (line.equals("DIAG")) {
                    System.out.println(Base64.getEncoder().encodeToString(relay.diagnostic().getBytes(StandardCharsets.UTF_8))
                        + "\t" + relay.needsAttention());
                    System.out.flush();
                }
            }
        }
    }
}'''


class Origin(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    records: list[dict] = []
    lock = threading.Lock()

    def log_message(self, *args):
        pass

    def send(self, status, body=b"", content_type="application/octet-stream", extra=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        row = {"method": self.command, "path": self.path, "headers": dict(self.headers),
               "cookies": self.headers.get_all("Cookie", [])}
        with self.lock:
            self.records.append(row)
        if (self.headers.get("Referer") != REFERER or self.headers.get("User-Agent") != AGENT
                or COOKIE not in "; ".join(row["cookies"])):
            return self.send(403, b"missing fixture headers", "text/plain")
        path = urlsplit(self.path).path
        if path == "/entry":
            return self.send(302, extra={"Location": "/catalog/master?redirect_sig=redirect_secret%2B",
                                         "Set-Cookie": "relay_session=session_secret; Path=/"})
        if path == "/catalog/master":
            manifest = ("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"audio\","
                        "URI=\"audio.m3u8?audio_sig=audio_secret%3D\"\n"
                        "#EXT-X-STREAM-INF:BANDWIDTH=1000000\n"
                        "variants/main.m3u8?" + SIGNED_QUERY + "\n")
            return self.send(200, manifest.encode(), "application/octet-stream")
        if path == "/gzip-playlist":
            manifest = b"#EXTM3U\n#EXTINF:6,\nsegments/one.ts?gzip_sig=gzip_secret\n#EXT-X-ENDLIST\n"
            return self.send(200, gzip.compress(manifest), "application/octet-stream", {"Content-Encoding": "gzip"})
        if path == "/bom-playlist":
            manifest = b"\xef\xbb\xbf#EXTM3U\n#EXTINF:6,\nsegments/one.ts?bom_sig=bom_secret\n#EXT-X-ENDLIST\n"
            return self.send(200, manifest, "application/octet-stream")
        if path == "/split-playlist":
            manifest = b"#EXTM3U\n#EXTINF:6,\nsegments/one.ts?split_sig=split_secret\n#EXT-X-ENDLIST\n"
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(manifest)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(manifest[:4])
            self.wfile.flush()
            threading.Event().wait(0.15)
            self.wfile.write(manifest[4:])
            return
        if path == "/without-length":
            self.send_response(200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(MEDIA)
            return
        if path == "/catalog/variants/main.m3u8":
            return self.send(200, b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nchild/media.m3u8?nested_sig=nested_secret\n",
                             "application/vnd.apple.mpegurl")
        if path == "/catalog/variants/child/media.m3u8":
            manifest = ("#EXTM3U\n#EXT-X-TARGETDURATION:6\n"
                        "#EXT-X-KEY:METHOD=AES-128,URI=\"../../../keys/key.bin?key_sig=key_secret%2B\"\n"
                        "#EXT-X-MAP:URI=\"../../init.mp4?map_sig=map_secret%3D\"\n"
                        "#EXTINF:6.0,\n../../../segments/one.ts?segment_sig=segment_secret%2F\n"
                        "#EXT-X-ENDLIST\n")
            return self.send(200, manifest.encode(), "application/vnd.apple.mpegurl")
        if path == "/catalog/audio.m3u8":
            return self.send(200, b"#EXTM3U\n#EXTINF:6,\n../segments/one.ts?audio_segment=audio_secret\n#EXT-X-ENDLIST\n",
                             "application/x-mpegURL")
        if path == "/keys/key.bin":
            return self.send(200, KEY)
        if path == "/catalog/init.mp4":
            return self.send(200, INIT, "video/mp4")
        if path == "/segments/one.ts":
            return self.send(200, SEGMENT, "video/mp2t")
        if path == "/movie.mp4":
            extra = {"Accept-Ranges": "bytes", "ETag": '"fixture-etag"',
                     "Last-Modified": "Wed, 25 Nov 2026 12:00:00 GMT"}
            requested = self.headers.get("Range")
            if requested:
                match = re.fullmatch(r"bytes=(\d+)-(\d*)", requested)
                if not match:
                    return self.send(416, extra={"Content-Range": f"bytes */{len(MEDIA)}"})
                start = int(match[1])
                end = int(match[2]) if match[2] else len(MEDIA) - 1
                if start >= len(MEDIA):
                    return self.send(416, extra={"Content-Range": f"bytes */{len(MEDIA)}"})
                end = min(end, len(MEDIA) - 1)
                extra["Content-Range"] = f"bytes {start}-{end}/{len(MEDIA)}"
                return self.send(206, MEDIA[start:end + 1], "video/mp4", extra)
            return self.send(200, MEDIA, "video/mp4", extra)
        if path == "/forbidden":
            return self.send(403, b"fixture_secret_error_body", "text/plain")
        if path == "/html":
            return self.send(200, b"<html>fixture_secret_error_body</html>", "text/html")
        return self.send(404, b"fixture path not found", "text/plain")


class Relay:
    def __init__(self, url):
        self.process = subprocess.Popen([str(JAVA), "-cp", str(WORK / "classes"),
                                         "RelayIntegrationHarness", url],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True, encoding="utf-8")
        self.url = self.process.stdout.readline().strip()
        if not self.url.startswith("http://127.0.0.1:"):
            raise RuntimeError("Harness did not expose a loopback URL: " + self.process.stderr.read())

    def diagnostic(self):
        self.process.stdin.write("DIAG\n")
        self.process.stdin.flush()
        encoded, attention = self.process.stdout.readline().strip().split("\t")
        return base64.b64decode(encoded).decode("utf-8"), attention == "true"

    def close(self):
        if self.process.poll() is None:
            self.process.stdin.write("CLOSE\n")
            self.process.stdin.flush()
        stdout, stderr = self.process.communicate(timeout=10)
        if self.process.returncode:
            raise RuntimeError(f"Harness exited {self.process.returncode}: {stderr}")


def request(url, method="GET", headers=None):
    parsed = urlsplit(url)
    conn = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=8)
    try:
        conn.request(method, parsed.path + ("?" + parsed.query if parsed.query else ""), headers=headers or {})
        reply = conn.getresponse()
        body = reply.read()
        return reply.status, dict(reply.getheaders()), body
    finally:
        conn.close()


def lines(manifest):
    return [line.strip() for line in manifest.decode().splitlines()
            if line.strip() and not line.strip().startswith("#")]


class RelayIntegration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        WORK.mkdir(parents=True, exist_ok=True)
        harness = WORK / "RelayIntegrationHarness.java"
        harness.write_text(HARNESS, encoding="utf-8")
        (WORK / "classes").mkdir(exist_ok=True)
        subprocess.run([str(JAVAC), "--release", "8", "-Xlint:-options", "-encoding", "UTF-8",
                        "-d", str(WORK / "classes"), str(harness),
                        str(ROOT / "src/com/github/tvbox/osc/skybox/StreamRelay.java")], check=True)
        cls.origin = ThreadingHTTPServer(("127.0.0.1", 0), Origin)
        cls.thread = threading.Thread(target=cls.origin.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.origin.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.origin.shutdown()
        cls.origin.server_close()
        cls.thread.join(timeout=5)

    def setUp(self):
        with Origin.lock:
            Origin.records.clear()
        self.relays = []

    def tearDown(self):
        for relay in self.relays:
            relay.close()

    def relay(self, path):
        relay = Relay(self.base + path)
        self.relays.append(relay)
        return relay

    def test_extensionless_hls_redirect_headers_and_signed_url(self):
        relay = self.relay("/entry?source_sig=source_secret%2B")
        status, headers, body = request(relay.url)
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "application/vnd.apple.mpegurl")
        self.assertIn(b"#EXTM3U", body)
        variant = lines(body)[0]
        self.assertTrue(variant.startswith("http://127.0.0.1:"))
        status, _, variant_body = request(variant)
        self.assertEqual(status, 200)
        status, _, _ = request(lines(variant_body)[0])
        self.assertEqual(status, 200)
        paths = [row["path"] for row in Origin.records]
        self.assertIn("/catalog/master?redirect_sig=redirect_secret%2B", paths)
        self.assertIn("/catalog/variants/main.m3u8?" + SIGNED_QUERY, paths)
        self.assertIn("/catalog/variants/child/media.m3u8?nested_sig=nested_secret", paths)
        for row in Origin.records:
            self.assertEqual(row["headers"].get("Referer"), REFERER)
            self.assertEqual(row["headers"].get("User-Agent"), AGENT)
            self.assertIn(COOKIE, "; ".join(row["cookies"]))
            self.assertEqual(row["headers"].get("Accept-Encoding"), "identity")
            self.assertNotEqual(row["headers"].get("Host"), "must-not-use.invalid")

    def test_key_map_segments_and_audio_uri_rewriting(self):
        relay = self.relay("/entry")
        _, _, master = request(relay.url)
        audio = re.search(rb'URI="([^"]+)"', master)[1].decode()
        self.assertEqual(request(audio)[0], 200)
        _, _, nested = request(lines(master)[0])
        _, _, media = request(lines(nested)[0])
        uris = [value.decode() for value in re.findall(rb'URI="([^"]+)"', media)]
        self.assertEqual(len(uris), 2)
        self.assertEqual(request(uris[0])[2], KEY)
        self.assertEqual(request(uris[1])[2], INIT)
        self.assertEqual(request(lines(media)[0])[2], SEGMENT)
        paths = [row["path"] for row in Origin.records]
        self.assertIn("/keys/key.bin?key_sig=key_secret%2B", paths)
        self.assertIn("/catalog/init.mp4?map_sig=map_secret%3D", paths)
        self.assertIn("/segments/one.ts?segment_sig=segment_secret%2F", paths)

    def test_mp4_full_bytes_and_ignore_original_range_header(self):
        relay = self.relay("/movie.mp4?token=movie_secret%2B")
        status, headers, body = request(relay.url)
        self.assertEqual((status, body), (200, MEDIA))
        self.assertEqual(headers["Content-Length"], str(len(MEDIA)))
        self.assertNotIn("Range", Origin.records[-1]["headers"])

    def test_range_206_and_seek_metadata(self):
        relay = self.relay("/movie.mp4")
        status, headers, body = request(relay.url, headers={"Range": "bytes=32-127", "If-Range": '"fixture-etag"'})
        self.assertEqual((status, body), (206, MEDIA[32:128]))
        self.assertEqual(headers["Content-Range"], f"bytes 32-127/{len(MEDIA)}")
        self.assertEqual(headers["Content-Length"], "96")
        self.assertEqual(headers["Accept-Ranges"], "bytes")
        self.assertEqual(headers["ETag"], '"fixture-etag"')
        self.assertEqual(Origin.records[-1]["headers"]["If-Range"], '"fixture-etag"')

    def test_head_retains_length_and_has_no_body(self):
        relay = self.relay("/movie.mp4")
        status, headers, body = request(relay.url, "HEAD")
        self.assertEqual((status, body), (200, b""))
        self.assertEqual(headers["Content-Length"], str(len(MEDIA)))
        self.assertEqual(Origin.records[-1]["method"], "HEAD")

    def test_head_range_retains_206(self):
        relay = self.relay("/movie.mp4")
        status, headers, body = request(relay.url, "HEAD", {"Range": "bytes=16-31"})
        self.assertEqual((status, body), (206, b""))
        self.assertEqual(headers["Content-Length"], "16")
        self.assertEqual(headers["Content-Range"], f"bytes 16-31/{len(MEDIA)}")

    def test_403_status_and_attention(self):
        relay = self.relay("/forbidden?token=forbidden_secret")
        status, _, body = request(relay.url)
        self.assertEqual((status, body), (403, b"fixture_secret_error_body"))
        diagnostic, attention = relay.diagnostic()
        self.assertTrue(attention)
        self.assertIn("403", diagnostic)
        self.assertNotIn("fixture_secret_error_body", diagnostic)

    def test_wrong_token_returns_404_without_upstream_request(self):
        relay = self.relay("/movie.mp4")
        parsed = urlsplit(relay.url)
        wrong = f"http://127.0.0.1:{parsed.port}/wrong-token/1/media.mp4"
        self.assertEqual(request(wrong)[0], 404)
        self.assertEqual(len(Origin.records), 0)

    def test_diagnostics_do_not_expose_parameters_or_header_values(self):
        relay = self.relay("/entry?source_sig=source_secret%2B")
        before, attention = relay.diagnostic()
        self.assertTrue(attention)
        request(relay.url)
        diagnostic, attention = relay.diagnostic()
        self.assertFalse(attention)
        for secret in ["source_secret", "redirect_secret", "cookie_secret", "referer_secret",
                       "session_secret", "TVBoxRelayIntegration/1.0", "source_sig", "redirect_sig"]:
            self.assertNotIn(secret, diagnostic)
        self.assertIn("Referer", diagnostic)
        self.assertIn("Cookie", diagnostic)

    def test_html_is_reported_as_bad_gateway(self):
        relay = self.relay("/html")
        status, _, body = request(relay.url)
        self.assertEqual(status, 502)
        diagnostic, attention = relay.diagnostic()
        self.assertTrue(attention)
        self.assertNotIn("fixture_secret_error_body", diagnostic)

    def test_method_not_allowed(self):
        relay = self.relay("/movie.mp4")
        self.assertEqual(request(relay.url, "POST")[0], 405)
        self.assertEqual(len(Origin.records), 0)

    def test_gzip_playlist_rewrites_and_recomputes_length(self):
        relay = self.relay("/gzip-playlist")
        status, headers, body = request(relay.url)
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "application/vnd.apple.mpegurl")
        self.assertEqual(int(headers["Content-Length"]), len(body))
        self.assertNotIn("Content-Encoding", headers)
        self.assertTrue(lines(body)[0].startswith("http://127.0.0.1:"))
        self.assertEqual(request(lines(body)[0])[2], SEGMENT)

    def test_redirect_set_cookie_reaches_playlist_and_children(self):
        relay = self.relay("/entry")
        _, _, body = request(relay.url)
        request(lines(body)[0])
        following = Origin.records[1:]
        self.assertGreaterEqual(len(following), 2)
        for row in following:
            self.assertIn("relay_session=session_secret", "; ".join(row["cookies"]))
            self.assertIn(COOKIE, "; ".join(row["cookies"]))

    def test_fragmented_m3u8_magic_is_sniffed(self):
        relay = self.relay("/split-playlist")
        status, headers, body = request(relay.url)
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "application/vnd.apple.mpegurl")
        self.assertTrue(lines(body)[0].startswith("http://127.0.0.1:"))
        self.assertEqual(request(lines(body)[0])[2], SEGMENT)

    def test_no_length_upstream_streams_until_connection_close(self):
        relay = self.relay("/without-length")
        status, headers, body = request(relay.url)
        self.assertEqual((status, body), (200, MEDIA))
        self.assertNotIn("Content-Length", headers)
        self.assertEqual(headers["Connection"], "close")

    def test_utf8_bom_playlist_preserves_magic_and_rewrites_links(self):
        relay = self.relay("/bom-playlist")
        status, headers, body = request(relay.url)
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "application/vnd.apple.mpegurl")
        self.assertTrue(body.startswith(b"#EXTM3U\n"))
        self.assertNotIn(b"\xef\xbb\xbf", body)
        child_urls = lines(body)
        self.assertEqual(len(child_urls), 1)
        self.assertTrue(child_urls[0].startswith("http://127.0.0.1:"))
        self.assertEqual(request(child_urls[0])[2], SEGMENT)
        self.assertIn("/segments/one.ts?bom_sig=bom_secret", [row["path"] for row in Origin.records])


if __name__ == "__main__":
    unittest.main(verbosity=2)
