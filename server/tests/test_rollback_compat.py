"""Rollback safety: objects written by this version must still parse with the Nov-5 live models (3ce525f)."""
import unittest

from app.ingest import td_carpark as td
from tests.legacy import models_3ce525f as old
from tests.test_ingest_td import NOW, FakeFetcher, FakeStore


class RollbackCompatTest(unittest.TestCase):
    def test_new_snapshot_objects_parse_with_old_models(self):
        store = FakeStore()
        td.run_once(store, FakeFetcher(), now=NOW)
        manifest = store.json(td.MANIFEST_OBJECT)
        old.SnapshotManifest.model_validate(manifest)
        for info in manifest["regions"].values():
            region = old.RegionSnapshot.model_validate(store.json(info["object"]))
            self.assertTrue(region.carparks)
            for c in region.carparks:
                self.assertGreaterEqual(c.vacancies, -1)


if __name__ == "__main__":
    unittest.main()
