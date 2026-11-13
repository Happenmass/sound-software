import unittest
from inspect_opus import boundaries


class BoundaryTests(unittest.TestCase):
    def test_explicit_units_do_not_guess_payload_location(self):
        spans, layout = boundaries(12, packet_size=4, stride=6, payload_offset=2)
        self.assertEqual(spans, [(2, 4), (8, 4)])
        self.assertEqual(layout["trailing_bytes"], 0)

    def test_trailing_bytes_remain_visible(self):
        spans, layout = boundaries(13, packet_size=4, stride=6, payload_offset=2)
        self.assertEqual(len(spans), 2)
        self.assertEqual(layout["trailing_bytes"], 1)

    def test_manifest_cannot_reorder_overlap_or_read_past_end(self):
        for entries in [[{"offset": 3, "length": 5}, {"offset": 5, "length": 1}],
                        [{"offset": 0, "length": 11}], [{"offset": -1, "length": 2}]]:
            with self.assertRaises(ValueError):
                boundaries(10, manifest=entries)

    def test_manifest_offsets_must_be_integers(self):
        with self.assertRaises(ValueError):
            boundaries(10, manifest=[{"offset": 0.5, "length": 4}])


if __name__ == "__main__":
    unittest.main()
