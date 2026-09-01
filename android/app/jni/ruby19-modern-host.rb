# Ruby 1.9's build tools predate the removal of the legacy TRUE/FALSE
# constants. Keep this compatibility confined to the host-side generator;
# neither constant is added to the target runtime.
TRUE = true unless Object.const_defined?(:TRUE)
FALSE = false unless Object.const_defined?(:FALSE)

# Ruby 1.9's rbinstall.rb calls FileUtils#install with its options hash as a
# third positional argument. Ruby 3 made those options keywords. Adapt only
# the host-side build helper so the unmodified release installer can run under
# the GitHub runner's Ruby without changing the target runtime.
require "fileutils"
module FileUtils
  alias enginehost_keyword_install install

  def install(src, dest, legacy_options = nil, **options)
    if legacy_options.is_a?(Hash)
      options = legacy_options.merge(options)
    elsif !legacy_options.nil?
      options[:mode] = legacy_options
    end
    enginehost_keyword_install(src, dest, **options)
  end
  module_function :install
end
