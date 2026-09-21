import unittest
from release_plugin import chacha20

class ReleaseCipherTest(unittest.TestCase):
    def test_standard_vector(self):
        expected = '76b8e0ada0f13d90405d6ae55386bd28bdd219b8a08ded1aa836efcc8b770dc7da41597c5157488d7724e03fb8d84a376a43b8f41518a11cc387b669b2ee6586'
        self.assertEqual(chacha20(bytes(64),bytes(32),bytes(12)).hex(),expected)
    def test_roundtrip(self):
        data = bytes(range(256))
        self.assertEqual(chacha20(chacha20(data,bytes(32),bytes(12)),bytes(32),bytes(12)),data)
if __name__ == '__main__': unittest.main()
