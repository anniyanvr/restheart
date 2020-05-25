@ignore
Feature: Opens Change Stream resources

Background:
* def authHeader = 'Basic YWRtaW46c2VjcmV0'
* url 'http://localhost:8080'
* def db = '/test-change-streams'
* def coll = db + '/coll'
# note: db starting with 'test-' are automatically deleted after test finishes


@requires-mongodb-3.6 @requires-replica-set
Scenario: Setup test environment

# Step 1: Create test database
    * header Authorization = authHeader
    Given path db
    And request {}
    When method PUT
    Then status 201

# Step 2: Create test collection
    * header Authorization = authHeader
    Given path coll
    And request {"streams": [{"stages": [], "uri": "changeStream" }, {"stages": [{"_$match": {"fullDocument.targettedProperty": {"_$var": "param"}}}], "uri": "changeStreamWithStageParam" }, {"stages":[{"_$match":{"fullDocument::name":"testname"}},{"_$match":{"_$or":[{"operationType":"insert"},{"operationType":"update"}]}}],"uri":"cs"},{"stages":[{"_$match":{"updateDescription::updatedFields::a":{"_$exists":true}}}],"uri":"ud"}]}
    When method PUT
    Then status 201
