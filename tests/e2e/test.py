import json
import logging
import os

import fhirclient.models.bundle as b
import pytest
import requests
from fhirclient import client
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

LOG = logging.getLogger(__name__)

BUNDLE_PATH = "data/bundle.json"
DELETE_BUNDLE_PATH = "data/delete-bundle.json"


@pytest.fixture(scope="session", autouse=True)
def wait_for_server_to_be_up(request):
    s = requests.Session()
    retries = Retry(total=15, backoff_factor=5, status_forcelist=[502, 503, 504])
    s.mount("http://", HTTPAdapter(max_retries=retries))

    print("FHIR: ", os.environ["FHIR_SERVER_URL"])
    response = s.get(
        os.environ["FHIR_SERVER_URL"] + "/metadata",
    )

    if response.status_code != 200:
        pytest.fail("Failed to wait for server to be up")


@pytest.fixture
def smart():
    settings = {"app_id": "integrationtest", "api_base": os.environ["FHIR_SERVER_URL"]}
    smart = client.FHIRClient(settings=settings)
    return smart


@pytest.fixture
def bundle():
    with open(BUNDLE_PATH, "r") as bundle_file:
        bundle_json = json.load(bundle_file)

    bundle = b.Bundle(bundle_json)
    return bundle


@pytest.fixture
def delete_bundle():
    with open(DELETE_BUNDLE_PATH, "r") as bundle_file:
        bundle_json = json.load(bundle_file)

    bundle = b.Bundle(bundle_json)
    return bundle


def test_observation_loinc_is_harmonized(smart, bundle):
    response_json = bundle.create(smart.server)
    observation_response = b.Bundle(response_json).entry[0].resource
    quantity = observation_response.valueQuantity

    assert quantity.value == 113.526
    assert quantity.unit == quantity.code == "mg/dL"


def test_observation_is_pseudonymized(smart, bundle):
    encounter_id_part = (
        bundle.entry[0].resource.encounter.processedReferenceIdentifier().split("/")[1]
    )
    subject_id_part = (
        bundle.entry[0].resource.subject.processedReferenceIdentifier().split("/")[1]
    )
    response_json = bundle.create(smart.server)
    bundle_response = b.Bundle(response_json)
    observation_response = bundle_response.entry[0].resource

    assert (
        encounter_id_part
        not in observation_response.encounter.processedReferenceIdentifier()
    )

    assert (
        subject_id_part
        not in observation_response.subject.processedReferenceIdentifier()
    )


def test_patient_is_pseudonymized(smart, bundle):
    original_patient_id = bundle.entry[1].resource.id
    response_json = bundle.create(smart.server)
    bundle_response = b.Bundle(response_json)

    assert original_patient_id not in bundle_response.entry[1].resource.id


def test_send_delete_bundle(smart, delete_bundle):
    response_json = delete_bundle.create(smart.server)
    bundle_response = b.Bundle(response_json)

    assert bundle_response
