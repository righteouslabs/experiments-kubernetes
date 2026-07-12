from .v1 import sample as sample_v1
from .v2 import sample as sample_v2
from .v3 import sample as sample_v3

EMITTERS = {
    "v1": sample_v1,
    "v2": sample_v2,
    "v3": sample_v3,
}
