local car = dofile('/opt/car.lua')
local base_setup = car.setup
local base_process_way = car.process_way

car.setup = function()
  local profile = base_setup()
  profile.properties.left_hand_driving = true
  return profile
end

car.process_way = function(profile, way, result, relations)
  base_process_way(profile, way, result, relations)

  local highway = way:get_value_by_key('highway')
  local preference = {
    unclassified = 0.70,
    residential = 0.55,
    living_street = 0.40,
    service = 0.25
  }
  local multiplier = preference[highway]
  if multiplier then
    if result.forward_rate and result.forward_rate > 0 then
      result.forward_rate = result.forward_rate * multiplier
    end
    if result.backward_rate and result.backward_rate > 0 then
      result.backward_rate = result.backward_rate * multiplier
    end
  end
end

return car
