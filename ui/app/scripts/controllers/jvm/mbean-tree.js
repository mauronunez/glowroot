/*
 * Copyright 2014-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* global glowroot, angular */

glowroot.controller('JvmMBeanTreeCtrl', [
  '$scope',
  '$location',
  '$http',
  'httpErrors',
  'queryStrings',
  function ($scope, $location, $http, httpErrors, queryStrings) {

    $scope.$parent.heading = 'MBean tree';

    if ($scope.hideMainContent()) {
      return;
    }

    var expandedObjectNames = $location.search().expanded || [];
    if (!angular.isArray(expandedObjectNames)) {
      expandedObjectNames = [expandedObjectNames];
    }

    function updateLocation() {
      var query = {};
      if ($scope.layout.central) {
        query['server-id'] = $scope.serverId;
      }
      query.expanded = expandedObjectNames;
      $location.search(query).replace();
    }

    $scope.toggleMBean = function (node) {
      if (node.expanded) {
        var index = expandedObjectNames.indexOf(node.objectName);
        expandedObjectNames.splice(index, 1);
        updateLocation();
        node.expanded = false;
        node.attributeMap = undefined;
        return;
      }
      expandedObjectNames.push(node.objectName);
      updateLocation();
      node.loading = true;
      node.expanded = true;
      var queryData = {
        serverId: $scope.serverId,
        objectName: node.objectName
      };
      $http.get('backend/jvm/mbean-attribute-map' + queryStrings.encodeObject(queryData))
          .success(function (data) {
            node.loading = false;
            node.attributeMap = data;
          })
          .error(function (data, status) {
            node.loading = false;
            httpErrors.handler($scope)(data, status);
          });
    };

    $scope.isSimpleValue = function (value) {
      return !angular.isObject(value) && !angular.isArray(value);
    };

    $scope.indentedJson = function (value) {
      return JSON.stringify(value, null, 4);
    };

    $scope.refresh = function (deferred) {
      var queryData = {
        serverId: $scope.serverId,
        expanded: expandedObjectNames
      };
      $http.get('backend/jvm/mbean-tree' + queryStrings.encodeObject(queryData))
          .success(function (data) {
            $scope.loaded = true;
            $scope.agentNotConnected = data.agentNotConnected;
            if ($scope.agentNotConnected) {
              return;
            }
            $scope.mbeanTree = data;
            if (deferred) {
              deferred.resolve('Refreshed');
            }
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.refresh();
  }
]);
