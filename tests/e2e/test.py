import os
import pytest
from fhirclient import client
import fhirclient.models.patient as p
import fhirclient.models.condition as c
import fhirclient.models.encounter as e
import fhirclient.models.observation as o
import requests
import json
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
from retrying import retry
import logging

LOG = logging.getLogger(__name__)


@pytest.fixture(scope="session", autouse=True)
def wait_for_server_to_be_up(request):
    s = requests.Session()
    retries = Retry(total=15, backoff_factor=5, status_forcelist=[502, 503, 504])
    s.mount("http://", HTTPAdapter(max_retries=retries))

    print("FHIR: ", os.environ["FHIR_SERVER_URL"])
    response = s.get(os.environ["FHIR_SERVER_URL"] + "/metadata",)

    if response.status_code != 200:
        pytest.fail("Failed to wait for server to be up")


@pytest.fixture
def smart():
    settings = {"app_id": "integrationtest", "api_base": os.environ["FHIR_SERVER_URL"]}
    smart = client.FHIRClient(settings=settings)
    return smart


def test_observation_loinc_is_harmonized(smart):
    with open("data/observation.json", "r") as obs_file:
        obs_json = json.load(obs_file)

    observation = o.Observation(obs_json)

    response_json = observation.update(smart.server)

    observation_response = o.Observation(response_json)

    quantity = observation_response.valueQuantity

    assert quantity.value == 113.526
    assert quantity.unit == quantity.code == "mg/dL"


def test_observation_is_pseudonymized(smart):
    with open("data/observation.json", "r") as obs_file:
        obs_json = json.load(obs_file)

    obs = o.Observation(obs_json)
    encounter_id_part = obs.encounter.processedReferenceIdentifier().split("/")[1]
    subject_id_part = obs.subject.processedReferenceIdentifier().split("/")[1]

    response_json = obs.update(smart.server)

    observation_response = o.Observation(response_json)

    assert (
        not encounter_id_part
        in observation_response.encounter.processedReferenceIdentifier()
    )

    assert (
        not subject_id_part
        in observation_response.subject.processedReferenceIdentifier()
    )
