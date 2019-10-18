"""Module for quickly logging data with HTML reports, since xml/html is both human readable and machine readable."""
import os
import sys
import subprocess
import textwrap
import glob
import collections
import inspect
import shutil
import numpy as np

from PIL import Image as PILImage
from bs4 import BeautifulSoup
from matplotlib import pylab


def to_css_string(style_dictionary, multi_line=False):
    """Return a string that could be used as an HTML element attribute."""
    before_string = after_string = ""
    join_string = " "
    if multi_line:
        after_string = "\n"
        before_string = join_string = "\n    "
    style_strings = []
    for attribute_name, attribute_value in style_dictionary.items():
        if isinstance(attribute_value, dict):
            style_strings.append(attribute_name + " { " + to_css_string(attribute_value, multi_line).strip() +
                                 " }")
        elif attribute_value is None:
            style_strings.append(attribute_name)
        else:
            style_strings.append(attribute_name + ": " + str(attribute_value) + ";")
    return before_string + join_string.join(style_strings) + after_string


def from_css_string(string):
    """Return a dictionary from the style string."""
    items = string.split(";")
    dictionary = collections.OrderedDict()
    for item in items:
        key, value = item.split(":")
        if value.startswith('"') and value.endswith('"'):
            value = value[1:-1]
        dictionary[key] = value
    return dictionary


def class_name_to_function_name(name):
    """Convert a python class name in CamelCase to a lower case function_name with underscores."""
    function_name = ""
    for char_idx, char in enumerate(name):
        if char == char.upper() and char_idx > 0 and name[char_idx - 1] == name[char_idx - 1].lower():
            function_name += "_"
        function_name += char.lower()
    return function_name


class ReversibleTransform(object):
    """A ReversibleTransform is a map between a python object and some output based on that object, such as a list of values
    from a list of object properties. Each transform provides the function get(*args, **kwargs), which produces the output.
    Each transform also provides a set(value, *args, **kwargs) function, where 'value' is the same type as the output of
    get() and the base object gets updated in a way that calling get() will return value."""
    def get(self, *args, **kwargs):
        raise NotImplementedError

    def set(self, *args, **kwargs):
        raise NotImplementedError

    def get_base_object(self, *args, **kwargs):
        base_object = self.base_object
        if isinstance(base_object, ReversibleTransform):
            base_object = base_object.get(*args, **kwargs)
        return base_object


class GetFromArgs(ReversibleTransform):
    """Maps a list of args to an object."""
    def __init__(self, argument_index=0):
        self.argument_index = argument_index

    def get(self, *args, **kwargs):
        return args[self.argument_index]


class GetFromKwArgs(ReversibleTransform):
    """Maps a dictionary of keyword arguments to an object."""
    def __init__(self, argument_keyword):
        self.argument_keyword = argument_keyword

    def get(self, *args, **kwargs):
        return kwargs[self.argument_keyword]


class ReversibleAttribute(ReversibleTransform):
    """A value, which comes from a property of the base object."""
    def __init__(self, base_object, attribute):
        self.base_object = base_object
        self.attribute = attribute

    def get_attribute(self, *args, **kwargs):
        attribute = self.attribute
        if isinstance(attribute, ReversibleTransform):
            attribute = attribute.get(*args, **kwargs)
        return attribute

    def get(self, *args, **kwargs):
        base_object = self.get_base_object(*args, **kwargs)
        attribute = self.get_attribute(*args, **kwargs)
        return getattr(base_object, attribute.replace("-", "_"))

    def set(self, value, *args, **kwargs):
        base_object = self.get_base_object(*args, **kwargs)
        attribute = self.get_attribute(*args, **kwargs)
        setattr(base_object, attribute.replace("-", "_"), value)


class ReversibleAttributeList(ReversibleAttribute):
    """A list of values, which come from accessing a list of base object properties, where the list is a property on the
    base object."""
    def get(self, *args, **kwargs):
        base_object = self.get_base_object(*args, **kwargs)
        attribute_list = self.get_attribute(*args, **kwargs)
        return [getattr(base_object, attribute.replace("-", "_")) for attribute in attribute_list]

    def set(self, value, *args, **kwargs):
        """value is a list corresponding to self.attribute_list."""
        base_object = self.get_base_object(*args, **kwargs)
        attribute_list = self.get_attribute(*args, **kwargs)
        if len(attribute_list) != len(value):
            raise Exception("List of attributes has length " + str(len(attribute_list)) + ", but list of values has length "
                            + str(len(value)))
        for attribute, attribute_value in zip(attribute_list, value):
            setattr(base_object, attribute.replace("-", "_"), attribute_value)


class ReversibleAttributeDictionary(ReversibleAttribute):
    """A dictionary, which comes from accessing a list of base object properties and pairing property name with value
    to form a dictionary, where the list is a property on the base object."""
    def get(self, *args, **kwargs):
        base_object = self.get_base_object(*args, **kwargs)
        attribute_list = self.get_attribute(*args, **kwargs)
        return collections.OrderedDict([(attribute, getattr(base_object, attribute.replace("-", "_"))) for attribute in
                                        attribute_list])

    def set(self, value, *args, **kwargs):
        """value is a dictionary."""
        base_object = self.get_base_object(*args, **kwargs)
        attribute_list = self.get_attribute(*args, **kwargs)
        for attribute, item_value in value.items():
            if attribute not in attribute_list:
                raise Exception("attribute " + str(attribute) + " not in attribute_list: " + str(attribute_list))
            setattr(base_object, attribute.replace("-", "_"), item_value)


class ReversibleDictionaryCopy(ReversibleAttribute):
    """A dictionary, where the dictionary is a property of the base object. Setting the value copies the dictionary instead
    of setting the object property directly to the dictionary."""
    def set(self, value, *args, **kwargs):
        """value is a dictionary."""
        base_object = self.get_base_object(*args, **kwargs)
        attribute = self.get_attribute(*args, **kwargs)
        destination_dictionary = getattr(base_object, attribute.replace("-", "_"))
        destination_dictionary.update(value)


class ReversibleChain(ReversibleTransform):
    """The output of the final transform in a sequence. get() moves forward through the sequence and sets the output
    of each transform as the input of the next transform. set(value, ...) moves backwards and should set each item
    with the same value. Not sure if this is ever used anywhere."""
    def __init__(self, sequence):
        self.sequence = sequence

    def get(self, *args, **kwargs):
        value = self.sequence[0].get(*args, **kwargs)
        for item in self.sequence[1:]:
            item.set(value, *args, **kwargs)
            value = item.get(*args, **kwargs)
        return value

    def set(self, value, *args, **kwargs):
        for item_idx in range(len(self.sequence) - 1, -1, -1):
            self.sequence[item_idx].set(value, *args, **kwargs)
            value = self.sequence[item_idx].get(*args, **kwargs)


class Markup(object):
    """The structure of a markup item such as XML or HTML, including the tag, attributes, and child elements. This class
    is meant to be instantiated as a class attribute in another class as a way to define how to translate that class into
    markup. Ideally, there should be no object data stored in this Markup object, only the map or structure to go from
    object to markup. The markup string can be accessed by calling markup_string(...) and providing the object instance."""

    def __init__(self, tag, attributes=None, children=None, tag_map=None, attributes_map=None, children_map=None,
                 no_children=False, tag_around_children=None, child_names_as_tags=False, replace_xml_characters=True):
        """Inputs:
            children - a list of attributes in the object whose values are markup children to the object. If children only
                contains a single item, the value can be anything. If children is a list of multiple items, the
                values should be objects that contain a Markup attribute, so that they can be properly represented in markup.
                The child attribute names are not displayed as tag.
            children_map - an optional function that replaces children_map() in this class, taking parent as a parameter
                and returning a list of child values.
            attributes - a list of attribute names in the object that are markup attributes to the object. The names will be
                interpreted as the attribute names in the tag. The values are the actual values of that attribute in the
                parent object.
            attributes_map - an optional function that replaces attributes_map() in this class, taking parent as a parameter
                and returning a dictionary of {"attribute1": "value1", "attribute2": "value2", ...}
            tag_around_children - an optional string that creates an additional tag around the children. Default is no tag.
            child_names_as_tags - if True, insert the child attribute name as a tag around the child value (if the child
                is a simple value, not an object with a markup attribute)
        """
        self.tag = tag
        self.no_children = no_children
        self.tag_around_children = tag_around_children
        self.child_names_as_tags = child_names_as_tags
        self.parent_map = GetFromArgs(0)
        self.replace_xml_characters = replace_xml_characters
        self.use_close_tag = True
        if children:
            self.children = children
        else:
            self.children = []
        if attributes:
            self.attributes = attributes
        else:
            self.attributes = []
        if tag_map:
            self.tag_map = tag_map
        else:
            self.tag_map = ReversibleAttribute(self, "tag")
        if attributes_map:
            self.attributes_map = attributes_map
        else:
            self.attributes_map = ReversibleAttributeDictionary(self.parent_map, ReversibleAttribute(self, "attributes"))
        if children_map:
            self.children_map = children_map
        else:
            self.children_map = ReversibleAttributeList(self.parent_map, ReversibleAttribute(self, "children"))

    # ---Markup options---
    def use_tag_property(self, name):
        """Change tag_map to use property 'name' in parent as the markup tag."""
        self.tag_map = ReversibleAttribute(self.parent_map, name)

    def use_attributes_direct(self, name):
        """Change attributes_map to use property 'name' in parent as a direct dictionary of attributes."""
        self.attributes_map = ReversibleDictionaryCopy(self.parent_map, name)

    def use_attribute_list(self):
        """Use markup.attributes as a list of parent properties that correspond to attribute names."""

    def keep_raw_text(self):
        self.replace_xml_characters = False

    def use_child_list(self, name, tag_around_children=False):
        """Change children_map to use attribute 'name' in parent as a list of children.
        include_name_in_tag - create a child tag to encompass that list."""
        self.tag_around_children = None
        if tag_around_children:
            self.tag_around_children = name
        self.children_map = ReversibleAttribute(self.parent_map, name)

    def use_child_field_list(self, name, tag_around_children=False):
        """Change children_map to use 'name' in parent as a list of child markup attribute names. This allows for
        dynamic markup where the fields are not known ahead of time or vary for each object."""
        self.tag_around_children = None
        if tag_around_children:
            self.tag_around_children = name
        self.children_map = ReversibleAttributeList(self.parent_map, ReversibleAttribute(self.parent_map, name))

    # ---Markup utility functions---
    @classmethod
    def attribute_string(cls, attributes, replace_xml_characters=True):
        """Return the attribute string (everything between the tag name and closing bracket) from the attribute
        dictionary."""
        if not attributes:
            return ""
        if replace_xml_characters:
            string = " " + " ".join([key + '="' + str(value).replace('"', '&quot;') + '"' for key, value in
                                     attributes.items()])
        else:
            string = " " + " ".join([key + '="' + str(value) + '"' for key, value in attributes.items()])
        return string

    @classmethod
    def markup_open(cls, tag, attributes, level, indent="  ", no_children=False, replace_xml_characters=True):
        """Static method for creating a markup open tag string."""
        closing_bracket = ">"
        if no_children:
            closing_bracket = "/>"
        return (indent * level) + "<" + tag + cls.attribute_string(attributes,
                replace_xml_characters=replace_xml_characters) + closing_bracket

    @classmethod
    def markup_close(cls, tag, level, indent="  "):
        """Static method for creating a markup closing tag string."""
        return (indent * level) + "</" + tag + ">"

    def open_tag(self, parent, level, indent):
        """Return the opening tag string for this element."""
        return self.markup_open(self.tag_map.get(parent), self.attributes_map.get(parent), level, indent, self.no_children,
                                replace_xml_characters=self.replace_xml_characters)

    def close_tag(self, parent, level, indent):
        """Return the closing tag for this element."""
        if not self.use_close_tag:
            return ""
        return self.markup_close(self.tag_map.get(parent), level, indent)


    def contents(self, parent, level, indent, quote_strings=False):
        """Return the contents of this item (the text between the opening and closing tag)."""
        string = ""
        if self.tag_around_children:
            string += self.markup_open(self.tag_around_children, {}, level, indent) + "\n"
            level += 1
        child_names = []
        if self.child_names_as_tags:
            child_names = self.children_map.get_attribute(parent)
        for child_idx, child in enumerate(self.children_map.get(parent)):
            child_knows_markup = hasattr(child, "markup") and hasattr(child.markup, "to_markup_string")
            if self.child_names_as_tags and not child_knows_markup:
                string += self.markup_open(child_names[child_idx], {}, level, indent)
            if child_knows_markup:
                # A child that knows how to translate itself into markup
                string += child.markup.to_markup_string(child, level, indent, quote_strings)
            elif quote_strings and isinstance(child, str):
                # A string that should be quoted
                string += '"' + str(child).replace('"', '\'') + '"'
            elif isinstance(child, str):
                # A string
                string += child
            else:
                # Anything else
                child_string = str(child)
                if self.replace_xml_characters:
                    child_string = child_string.replace("<", "&lt;").replace(">", "&gt;")
                string += child_string
            if self.child_names_as_tags and not child_knows_markup:
                string += self.markup_close(child_names[child_idx], 0, indent) + "\n"

        if self.tag_around_children:
            level -= 1
            string += self.markup_close(self.tag_around_children, level, indent) + "\n"
        return string

    def to_markup_string(self, parent, level=0, indent="  ", quote_strings=False):
        """Return a string representation of this element in markup."""
        string = self.open_tag(parent, level, indent)
        if not self.no_children:
            contents = self.contents(parent, level + 1, indent, quote_strings=quote_strings)
            separator_string = ""
            close_level = 0
            if (contents.strip().startswith("<") and contents.strip().endswith(">")) or contents.find("\n") != -1:
                separator_string = "\n"
                close_level = level
            string += separator_string + contents
            string += self.close_tag(parent, close_level, indent)
        string += "\n"
        return string

    def from_markup_string(self, parent, markup_string=None, string_format="xml", soup=None, tag_class_dictionary=None,
                           strings_were_quoted=False):
        """Use the markup string or BeautifulSoup object provided to reconstruct the fields of the parent."""
        if soup is None:
            if markup_string is None:
                raise Exception("Either markup_string or soup must be provided.")
            soup = BeautifulSoup(markup_string, string_format).find()
        # Set tag
        self.tag_map.set(soup.name, parent)
        # Set attributes
        self.attributes_map.set(soup.attrs, parent)
        # Set children
        soup_children = [child for child in soup.children if child != "\n"]
        if self.tag_around_children and soup_children:
            soup_children = [child for child in soup_children[0] if child != "\n"]
        if tag_class_dictionary is None:
            children = soup_children
        else:
            children = []
            for child in soup_children:
                if child.name in tag_class_dictionary.keys():
                    child_object = tag_class_dictionary[child.name]()
                    child_object.markup.from_markup_string(child_object, soup=child,
                            tag_class_dictionary=tag_class_dictionary, strings_were_quoted=strings_were_quoted)
                    children.append(child_object)
                else:
                    if strings_were_quoted:
                        child = eval(child)
                    children.append(child)
        self.children_map.set(children, parent)


class XmlBase(object):
    """An XML base class that other classes can inherit in order to get convenience 'to_xml' and 'from_xml' functions.
    The subclass must also define the XML structure with a Markup attribute named 'markup'."""
    def to_xml(self, level=0, indent_string="  ", quote_strings=False):
        return self.markup.to_markup_string(self, level=level, indent=indent_string, quote_strings=quote_strings)

    def from_xml(self, xml_string=None, soup=None, tag_class_dictionary=None, strings_were_quoted=False):
        """Assign values to this object based on the BeautifulSoup object xml_object."""
        # tag_class_dictionary = dict([(class_.markup.tag_map.get(self), class_) for class_ in list_of_classes])
        return self.markup.from_markup_string(self, markup_string=xml_string, soup=soup,
                tag_class_dictionary=tag_class_dictionary, strings_were_quoted=strings_were_quoted)


class HtmlBase(object):
    """An HTML base class that other classes can inherit in order to get convenience 'to_html' and 'from_html' functions,
    as well as a css dictionary. Each subclass must define the HTML mapping with a Markup attribute named 'markup'."""
    def __init__(self, css=None, attributes=None):
        if css:
            self.css = css
        else:
            self.css = {}
        if attributes:
            self.attributes = attributes
        else:
            self.attributes = collections.OrderedDict()
        self.markup.use_attributes_direct("html_attributes")

    def inline(self):
        """Create an HTML string that can be used within a paragraph."""
        return self.to_html().replace("\n", "")

    @property
    def html_attributes(self):
        """Return a combined dictionary of attributes and css "style" that would go in the HTML opening tag."""
        attributes = self.attributes.copy()
        if self.css:
            attributes["style"] = to_css_string(self.css)
        return attributes

    @html_attributes.setter
    def html_attributes(self, attributes):
        """Return a combined dictionary of attributes and css "style" that would go in the HTML opening tag."""
        self.attributes = collections.OrderedDict(attributes)
        self.css = collections.OrderedDict()
        if "style" in self.attributes.keys():
            self.css = from_css_string(self.attributes.pop("style"))

    def to_html(self, level=0, indent_string="  "):
        """Return an HTML string of this element and all of its children."""
        return self.markup.to_markup_string(self, level=level, indent=indent_string, quote_strings=False)

    # def from_html(self, html_string=None, soup=None, tag_class_dictionary=None):
    #     """Assign values to this object based on the BeautifulSoup object xml_object."""
    #     if tag_class_dictionary is None:
    #         tag_class_dictionary = {}
    #         for name, obj in inspect.getmembers(sys.modules[__name__]):
    #             if inspect.isclass(obj) and issubclass(obj, HtmlBase) and hasattr(obj, "markup"):
    #                 tag_class_dictionary[obj.markup.tag] = obj
    #     return self.markup.from_markup_string(self, markup_string=html_string, soup=soup,
    #             tag_class_dictionary=tag_class_dictionary, strings_were_quoted=False)

    def save(self, filename):
        """Save the HTML string to the given file."""
        html_string = self.to_html()
        with open(filename, 'wt') as file_out:
            file_out.write(html_string)

    def set_binary_css(self, property_dictionary, boolean):
        """Update the css style with the given property dictionary if boolean is True, otherwise, remove those dictionary
        items from the css dictionary."""
        if boolean:
            self.css.update(property_dictionary)
        else:
            for key, value in property_dictionary.items():
                self.css.pop(key, None)

    def set_bold(self, boolean):
        """Set the bold property of the style for this HTML element."""
        self.set_binary_css({"font-weight": "bold"}, boolean)

    def set_font_size(self, font_size, units="px"):
        """Set the font-size of this HTML element."""
        self.css["font-size"] = str(font_size) + units

    def set_font(self, font_family):
        """Set the font-family of this HTML element."""
        self.css["font-family"] = font_family

    def set_color(self, color):
        """Set the color of this HTML element."""
        self.css["color"] = html_color(color)

    def set_margin(self, margin):
        """Set the margin of this HTML element."""
        self.css["margin"] = margin

    def set_padding(self, padding):
        """Set the color of this HTML element."""
        self.css["padding"] = padding

    def set_text_align(self, align="left"):
        """Set the text-align of this HTML element."""
        self.css["text-align"] = align

    def set_border(self):
        """Set the class of this element to a pre-defined class which contains a border."""
        classes = self.attributes.get("class", "")
        if classes.find("border") != -1:
            return
        if classes:
            classes += " "
        classes += "border"
        self.attributes["class"] = classes

    def set_width(self, width, units="px"):
        """Set the width of this HTML element."""
        if type(width) == str:
            self.css["width"] = width
        else:
            self.css["width"] = str(width) + units

    def set_height(self, height, units="px"):
        """Set the width of this HTML element."""
        if type(height) == str:
            self.css["height"] = height
        else:
            self.css["height"] = str(height) + units

    def __str__(self):
        return self.to_html()


class Xml(XmlBase):
    """A generic top level <Xml> item."""
    markup = Markup("Xml")
    markup.use_child_list("children")

    def __init__(self, children=None):
        self.children = children


# ---HTML elements---
class Div(HtmlBase):
    """A <div> element."""
    markup = Markup("div")
    markup.use_child_list("children")

    def __init__(self, children=None, css=None, attributes=None):
        super().__init__(css=css, attributes=attributes)
        if children is not None:
            self.children = children
        else:
            self.children = []

    def add(self, html_object):
        self.children.append(html_object)
        return html_object


class Audio(HtmlBase):
    """An <audio controls> element for playing audio in browser."""
    markup = Markup("audio")
    markup.use_child_list("children")

    def __init__(self, source=None, type_="audio/mpeg", additional_source_type_tuples=None, css=None, attributes=None):
        """type_ is a mime type: 'audio/mpeg', 'audio/wav', etc."""
        if attributes is None:
            attributes = collections.OrderedDict()
        attributes["controls"] = ""
        super(Audio, self).__init__(css=css, attributes=attributes)
        self.children = []
        if source:
            self.children.append(Source(source, type_))
        if additional_source_type_tuples:
            for other_source, other_type in additional_source_type_tuples:
                self.children.append(Source(other_source, other_type))


class Source(HtmlBase):
    """A <source> element."""
    markup = Markup("source", no_children=True)

    def __init__(self, source, type_, css=None, attributes=None):
        attrs = collections.OrderedDict([("src", source.replace(" ", "%20")), ("type", type_)])
        if attributes:
            attrs.update(attributes)
        super(Source, self).__init__(css=css, attributes=attrs)


class Strong(HtmlBase):
    """A <strong> element."""
    markup = Markup("strong")
    markup.children = ["text"]

    def __init__(self, text="", css=None, attributes=None):
        super().__init__(css=css, attributes=attributes)
        self.text = text


class Button(HtmlBase):
    """A <button> element."""
    markup = Markup("button", tag_around_children=False)
    markup.children = ["text"]

    def __init__(self, text="", css=None, attributes=None):
        super(Button, self).__init__(css=css, attributes=attributes)
        self.text = text


class Break(HtmlBase):
    """A <br> element."""
    markup = Markup("br", no_children=True)


class Canvas(HtmlBase):
    """A <canvas> element."""
    markup = Markup("canvas")
    markup.children = []

    def __init__(self, css=None, attributes=None):
        super(Canvas, self).__init__(css=css, attributes=attributes)


class H(HtmlBase):
    """An <h1>, <h2>, <h3>, ... or <h6> element."""
    markup = Markup("")
    markup.use_tag_property("tag")
    markup.children = ["text"]

    def __init__(self, text, header_level=1, css=None, attributes=None):
        super().__init__(css=css, attributes=attributes)
        self.text = text
        self.header_level = header_level

    @property
    def tag(self):
        """Return the HTML tag."""
        return "h" + str(self.header_level)

    @tag.setter
    def tag(self, string):
        """Set the header level from the HTML tag."""
        self.header_level = int(string.lower().replace("h", ""))


class Figcaption(HtmlBase):
    """A <figcaption> element."""
    markup = Markup("figcaption")
    markup.children = ["text"]

    def __init__(self, text, css=None, attributes=None):
        super().__init__(css=css, attributes=attributes)
        self.text = text


class Figure(HtmlBase):
    """A <figure> element."""
    markup = Markup("figure")
    markup.children = ["img", "figcaption"]

    def __init__(self, img, caption, css=None, attributes=None):
        super().__init__(css=css, attributes=attributes)
        self.img = img
        self.figcaption = Figcaption(caption)


class Footer(Div):
    """A <footer> (HTML5) element."""
    markup = Markup("footer")
    markup.use_child_list("children")


class Form(Div):
    """A <form> element."""
    markup = Markup("form")
    markup.use_child_list("children")

    def __init__(self, action=None, method="post", attributes=None, css=None):
        super().__init__(css=css, attributes=attributes)
        if action:
            self.attributes["action"] = action
        if method:
            self.attributes["method"] = method


class Header(HtmlBase):
    """A <header> (HTML5) element."""
    markup = Markup("header")
    markup.use_child_list("children")

    def __init__(self, css=None, attributes=None):
        super().__init__(css=css, attributes=attributes)
        self.children = []

    def add(self, html_object):
        self.children.append(html_object)
        return html_object


class Hyperlink(HtmlBase):
    """An <a href="..."> element."""
    markup = Markup("a")
    markup.use_child_list("children")

    def __init__(self, href, text=None, css=None, attributes=None, new_tab=False):
        super(Hyperlink, self).__init__(css=css, attributes=attributes)
        self.attributes["href"] = href
        if new_tab:
            self.attributes["target"] = "_blank"
        self.children = []
        self.link_text = text
        if text is not None:
            self.children.append(text)

    def add(self, html_object):
        self.children.append(html_object)
        return html_object

    def inline(self):
        """Create an HTML string that can be used within a paragraph."""
        return self.to_html().replace("\n", "")


# class LinearGradient(HtmlBase):
#     """A <linearGradient> element."""
#     markup = Markup("linearGradient")
#     markup.children = ["start_color", "stop_color"]
#
#     def __init__(self, start_color="#D7D7D7", stop_color, x1=0, x2=0, y1=0, y2=100, gradientUnits="userSpaceOnUse", css=None,
#                  attributes=None):
#         super(LinearGradient, self).__init__(css=css, attributes=attributes)
#         self.attributes["gradientUnits"] = gradientUnits
#         self.attributes["x1"] = str(x1) + "%"
#         self.attributes["x2"] = str(x2) + "%"
#         self.attributes["y1"] = str(y1) + "%"
#         self.attributes["y2"] = str(y2) + "%"


class Iframe(HtmlBase):
    """An <iframe> element."""
    markup = Markup("iframe")
    markup.use_child_list("children")

    def __init__(self, src="", width=None, height=None, attributes=None, css=None):
        super().__init__(css=css, attributes=attributes)
        self.attributes["src"] = src
        if width:
            self.attributes["width"] = str(width)
        if height:
            self.attributes["height"] = str(height)
        self.children = []


class Image(HtmlBase):
    """A <img> element."""
    markup = Markup("img", no_children=True)
    markup.keep_raw_text()

    def __init__(self, filename, width=None, height=None, alt=None, attributes=None, css=None):
        if attributes is None:
            attributes = collections.OrderedDict()
        attributes["src"] = filename.replace(" ", "%20")
        if width:
            attributes["width"] = width
        if height:
            attributes["height"] = height
        if alt:
            attributes["alt"] = alt
        super(Image, self).__init__(css=css, attributes=attributes)

    @property
    def filename(self):
        """Return the image file path."""
        return self.attributes["src"]

    @filename.setter
    def filename(self, value):
        """Set the image filename."""
        self.attributes["src"] = value


class ImageSlider(Div):
    """An image with a slider that changes the image source."""
    markup = Markup("div")
    markup.use_child_list("children")

    def __init__(self, html_document, images=None, images_path_list=None, image_width=None, image_height=None,
                 slider_width=100, pre_load_images=True, css=None, attributes=None):
        if images is not None:
            if image_height is None and image_width is None:
                image_height = images[0].height
                image_width = images[0].width
            if images_path_list is None:
                images_path_list = [image.attributes["src"] for image in images]
        if pre_load_images:
            image_slider_count = len([child for child in html_document.body.children if isinstance(child, ImageSlider)])
            self.slider_id = "imageSlider" + str(image_slider_count)
            self.image_ids = ["imageSlider" + str(image_slider_count) + "_Image" + str(idx) for idx in
                              range(len(images_path_list))]
            super(ImageSlider, self).__init__(css=css, attributes=attributes)
            self.children = []
            self.image_div = Div(css={"position": "relative", "display": "inline-block", "width": str(image_width) + "px",
                                      "height": str(image_height) + "px"})
            self.images = []
            for image_path, image_id in zip(images_path_list, self.image_ids):
                self.images.append(Image(image_path, width=image_width, height=image_height, css={"position": "absolute",
                        "left": "0", "top": "0", "opacity": "0"}, attributes={"id": image_id}))
            self.image_div.children = self.images
            self.images[0].css["opacity"] = "1.0"
            slider_div = Div(css={"width": "100%", "text-align": "center"})
            slider_div.add(Input("range", css={"width": str(slider_width) + "px"}, attributes={"value": "0", "min": "0",
                    "max": str(len(images_path_list) - 1), "id": self.slider_id}))
            self.children = [self.image_div, Break(), slider_div,
                    Script("setupPreLoadedImageSlider(\"" + self.slider_id + "\", [" +
                           ", ".join(['"' + image_id + '"' for image_id in self.image_ids]) + "]);")]
            indent = "      "
            script = Script(indent + "function setupPreLoadedImageSlider(sliderId, imageIds) {\n" +
            indent + "  var slider = document.getElementById(sliderId);\n" +
            indent + "  slider.oninput = function () {\n" +
            indent + "    for(var idx=0; idx<imageIds.length; idx++) {\n" +
            indent + "      var opacity = 0;\n" +
            indent + "      if(idx.toString() === slider.value) { opacity = 1.0; }\n" +
            indent + "      var image = document.getElementById(imageIds[idx]);\n" +
            indent + "      image.style.opacity = opacity;\n" +
            indent + "    }\n" +
            indent + "  };\n" +
            indent + "}")
            scripts = [child for child in html_document.body.children if isinstance(child, Script)]
            if script not in scripts:
                html_document.head.children.append(script)
        else:
            image_slider_count = len([child for child in html_document.body.children if isinstance(child, ImageSlider)])
            self.slider_id = "imageSlider" + str(image_slider_count)
            self.image_id = "imageSliderImage" + str(image_slider_count)
            super(ImageSlider, self).__init__(css=css, attributes=attributes)
            self.children = [Image(images_path_list[0], width=image_width, height=image_height, attributes={"id":
                                                                                                            self.image_id}),
                             Input("range", css={"width": slider_width + "px"}, attributes={"value": "0", "min": "0", "max":
                                   str(len(images_path_list) - 1), "id": self.slider_id}),
                             Script("setupImageSlider(\"" + self.slider_id + "\", \"" + self.image_id + "\", [" +
                                    ", ".join(['"' + path + '"' for path in images_path_list]) + "]);")]
            indent = "  "
            script = Script("function setupImageSlider(sliderId, imageId, imageList) {\n" +
            indent + "var slider = document.getElementById(sliderId);\n" +
            indent + "var image = document.getElementById(imageId);\n" +
            indent + "slider.oninput = function() { image.src = imageList[slider.value]; };\n" +
            indent + "}")
            scripts = [child for child in html_document.body.children if isinstance(child, Script)]
            if script not in scripts:
                html_document.head.children.append(script)


class Input(HtmlBase):
    """An <input> element."""
    markup = Markup("input")
    markup.use_close_tag = False

    def __init__(self, input_type, name=None, value=None, css=None, attributes=None):
        super(Input, self).__init__(css=css, attributes=attributes)
        self.attributes["type"] = input_type
        if name is not None:
            self.attributes["name"] = name
        if value is not None:
            self.attributes["value"] = value


class Label(HtmlBase):
    """A <label> element."""
    markup = Markup("label")
    markup.children = ["text"]

    def __init__(self, for_name, text, css=None, attributes=None):
        super().__init__(css=css, attributes=attributes)
        self.attributes["for"] = for_name
        self.text = text


class Li(HtmlBase):
    """An <li> element."""
    markup = Markup("li")
    markup.use_child_list("children")

    def __init__(self, text=None, css=None, attributes=None):
        super().__init__(css=css, attributes=attributes)
        self.children = []
        self.text = text
        if text is not None:
            self.children.append(text)

    def add(self, html_object):
        self.children.append(html_object)
        return html_object


class Link(HtmlBase):
    """A <link> element."""
    markup = Markup("link", no_children=True)

    def __init__(self, href="", type_="text/css", rel="stylesheet", sizes=None, css=None, attributes=None):
        super().__init__(css=css, attributes=attributes)
        self.attributes["rel"] = rel
        if type_ is not None:
            self.attributes["type"] = type_
        if sizes is not None:
            self.attributes["sizes"] = sizes
        self.attributes["href"] = href


class Meta(HtmlBase):
    """A <meta> element."""
    markup = Markup("meta")
    markup.no_children = True

    def __init__(self, attributes=None, css=None, add_charset=True):
        super().__init__(css=css, attributes=attributes)
        if add_charset:
            self.attributes["charset"] = "UTF-8"


class Nav(HtmlBase):
    """A <nav> element."""
    markup = Markup("nav")
    markup.use_child_list("children")

    def __init__(self, css=None, attributes=None):
        super().__init__(css=css, attributes=attributes)
        self.children = []

    def add(self, html_object):
        self.children.append(html_object)
        return html_object


class Option(HtmlBase):
    """An <option> element in a <select>."""
    markup = Markup("option")
    markup.children = ["description"]

    def __init__(self, value, description=None, selected=False, css=None, attributes=None):
        """selected=True makes the option selected by default"""
        if description is None:
            description = value
        super().__init__(css=css, attributes=attributes)
        self.attributes["value"] = value
        self.description = description
        if selected:
            self.attributes["selected"] = "selected"


class Span(Div):
    """A <span> element."""
    markup = Markup("span")
    markup.use_child_list("children")


class Script(HtmlBase):
    """A <script> element."""
    markup = Markup("script")
    markup.children = ["text"]

    def __init__(self, script_text="", src=None, script_type="text/javascript", css=None, attributes=None):
        super(Script, self).__init__(css=css, attributes=attributes)
        if script_text != "text/javascript":
            self.attributes["type"] = script_type
        self.text = script_text
        if src is not None:
            self.attributes["src"] = src

    def __eq__(self, other):
        return self.text == other.text

    def __ne__(self, other):
        return not self.__eq__(other)


class Section(HtmlBase):
    """A <section> (HTML5) element."""
    markup = Markup("section")
    markup.use_child_list("children")

    def __init__(self, css=None, attributes=None):
        super().__init__(css=css, attributes=attributes)
        self.children = []

    def add(self, html_object):
        self.children.append(html_object)
        return html_object


class Select(HtmlBase):
    """A <select> element."""
    markup = Markup("")
    markup.use_tag_property("tag")
    markup.use_child_list("options")

    def __init__(self, name=None, options=None, multiple=False, css=None, attributes=None):
        """To set the default option, set the option's "selected" attribute to "selected"."""
        super(Select, self).__init__(css=css, attributes=attributes)
        self.multiple = multiple
        self.options = []
        if options is not None:
            self.options = options
        if name is not None:
            self.attributes["name"] = name

    def add(self, option):
        """Add the given option to this select."""
        self.options.append(option)
        return option

    @property
    def tag(self):
        """Return the HTML tag."""
        if self.multiple:
            return "select multiple"
        return "select"

    @tag.setter
    def tag(self, string):
        self.multiple = False
        if string.find("multiple") != -1:
            self.multiple = True


class Style(HtmlBase):
    """An HTML document's style."""
    markup = Markup("style")

    def __init__(self, attributes=None, multi_line_threshold=2):
        super(Style, self).__init__(attributes=attributes)
        self.multi_line_threshold = multi_line_threshold
        self.styles = collections.OrderedDict()
        self.markup.contents = self.contents
        self.extra_text = ""  # Any raw CSS to go at the bottom

    def update(self, element, style_dictionary):
        """Copy the element's current dictionary and then update it with the dictionary provided."""
        self.styles[element] = self.styles[element].copy()
        self.styles[element].update(style_dictionary)

    def insert(self, element, style_dictionary, index):
        """Insert the style dictionary into the CSS ordered dictionary at the given index."""
        items = self.styles.items()
        items.insert(index, (element, style_dictionary))
        self.styles = collections.OrderedDict(items)

    def stylesheet_string(self, level=0, indent="  ", multi_line_threshold=None):
        """Return a string that could be used as a CSS stylesheet."""
        if multi_line_threshold is None:
            multi_line_threshold = self.multi_line_threshold
        lines = []
        for key, value in self.styles.items():
            if value:
                lines.extend((key + " { " + to_css_string(value, len(value) >= multi_line_threshold) + "}").splitlines())
        string = "\n".join([(level * indent) + line for line in lines])
        string += self.extra_text
        return string

    def contents(self, parent, level, indent, quote_strings=False):
        """Specify the contents between the open and close tag. This function is meant to override the default behavior."""
        return self.stylesheet_string(level, indent) + "\n"


class Paragraph(HtmlBase):
    """A <p> element."""
    markup = Markup("p")
    markup.children = ["html_text"]

    def __init__(self, text, print_=False, attributes=None, css=None, format_newlines=False):
        super(Paragraph, self).__init__(css=css, attributes=attributes)
        self.text = str(text)
        if format_newlines:
            self.text = self.text.replace("\n\n", "\r\r").replace("\n", " ").replace("\r", "\n")
        if print_:
            print(text)

    @property
    def html_text(self):
        """Format the paragraph text so that newlines are replaced with html breaks."""
        return self.text.replace("\n", "<br>")


class TableRow(HtmlBase):
    """A <tr> element."""
    markup = Markup("tr")
    markup.use_child_list("items")

    def __init__(self, table_items, row_headers, column_headers, is_first_row, attributes=None, css=None):
        super(TableRow, self).__init__(css=css, attributes=attributes)
        self.items = []
        for item_idx, item in enumerate(table_items):
            if is_first_row and column_headers or item_idx == 0 and row_headers:
                item_class = TableHeader
            else:
                item_class = TableItem
            self.items.append(item_class(item))


class TableHeader(HtmlBase):
    """A <th> element."""
    markup = Markup("th")
    markup.children = ["value"]

    def __init__(self, value, attributes=None, css=None):
        super(TableHeader, self).__init__(css=css, attributes=attributes)
        self.value = value


class TableItem(HtmlBase):
    """A <td> element."""
    markup = Markup("td")
    markup.children = ["value"]

    def __init__(self, value, attributes=None, css=None):
        super(TableItem, self).__init__(css=css, attributes=attributes)
        self.value = value


class Table(HtmlBase):
    """A <table> element, created from a python 1D or 2D list of values."""
    markup = Markup("table")
    markup.use_child_list("children")

    def __init__(self, *args, css=None, attributes=None, border=False, **kwargs):
        super().__init__(css=css, attributes=attributes)
        self.children = []
        self.tbody = Tbody(*args, **kwargs)
        self.rows = self.tbody.rows
        self.children.append(self.tbody)
        if border:
            self.set_border()


class Tbody(HtmlBase):
    """A <tbody> element, created from a python 1D or 2D list of values."""
    markup = Markup("tbody")
    markup.use_child_list("rows")

    def __init__(self, table_list, column_headers=False, row_headers=False, attributes=None, css=None,
                 title=None, title_column_span=None, transpose=False):
        super().__init__(css=css, attributes=attributes)
        if table_list and not hasattr(table_list[0], "__getitem__"):
            table_list = [table_list]
        if transpose:
            table_list = [[table_list[row_idx][col_idx] for row_idx in range(len(table_list))]
                          for col_idx in range(len(table_list[0]))]
        self.rows = [TableRow(row, row_headers, column_headers, row_idx == 0) for row_idx, row in enumerate(table_list)]
        if title is not None:
            column_count = title_column_span
            if title_column_span is None:
                column_count = len(self.rows[0].items)
            table_row = TableRow([title], False, True, True)
            table_row.items[0].attributes = {"colspan": str(column_count)}
            table_row.items[0].css["text-align"] = "center"
            self.rows.insert(0, table_row)


class TextArea(HtmlBase):
    """A <textarea> element."""
    markup = Markup("textarea")

    def __init__(self, name=None, rows=None, cols=None, attributes=None, css=None):
        super().__init__(css=css, attributes=attributes)
        if name is not None:
            self.attributes["name"] = name
        if rows is not None:
            self.attributes["rows"] = rows
        if cols is not None:
            self.attributes["cols"] = cols


# Deprecated
class RawCode(HtmlBase):
    """An <xmp> element for raw XML or HTML. Note, <xmp> is deprecated."""
    markup = Markup("xmp")
    markup.children = ["text"]

    def __init__(self, text, attributes=None, css=None):
        super(RawCode, self).__init__(css=css, attributes=attributes)
        self.text = text


class Code(HtmlBase):
    """A <code> element."""
    markup = Markup("code")
    markup.children = ["text"]

    def __init__(self, text, attributes=None, css=None):
        super().__init__(css=css, attributes=attributes)
        self.text = text


class Head(HtmlBase):
    """A <head> element."""
    markup = Markup("head")
    markup.use_child_list("children")

    def __init__(self, children=None, multi_line_threshold=2, attributes=None, style=None):
        super(Head, self).__init__(attributes=attributes)
        if style:
            self.style = style
        else:
            self.style = Style(multi_line_threshold=multi_line_threshold)
        self.children = [Meta(), self.style]
        if children:
            self.children.extend(children)

    def add(self, child):
        """Add the child element."""
        self.children.append(child)
        return child


class Body(HtmlBase):
    """A <body> element."""
    markup = Markup("body")
    markup.use_child_list("children")

    def __init__(self, attributes=None, css=None):
        super(Body, self).__init__(css=css, attributes=attributes)
        self.children = []


class Bold(HtmlBase):
    """A <b> element around text."""
    markup = Markup("b")
    markup.children = ["text_or_child"]

    def __init__(self, text_or_child=None, attributes=None, css=None):
        super(Bold, self).__init__(css=css, attributes=attributes)
        self.text_or_child = text_or_child

    def inline(self):
        """Create an HTML string that can be used within a paragraph."""
        return self.to_html().replace("\n", "")


class Title(HtmlBase):
    """A <title> element."""
    markup = Markup("title")
    markup.children = ["text"]

    def __init__(self, text, attributes=None, css=None):
        super(Title, self).__init__(css=css, attributes=attributes)
        self.text = text


class Ul(Div):
    """A <ul> element."""
    markup = Markup("ul")
    markup.use_child_list("children")


class Video(Div):
    """A <video> element."""
    markup = Markup("video")
    markup.use_child_list("children")

    def __init__(self, src=None, type_="video/mp4", width=None, height=None, controls=False, attributes=None,
                 css=None):
        super().__init__()
        if controls:
            self.attributes["controls"] = None
        if width is not None:
            self.attributes["width"] = width
        if height is not None:
            self.attributes["height"] = height
        if src:
            self.add(Source(src, type_))


class Ol(Div):
    """An <ol> element."""
    markup = Markup("ol")
    markup.use_child_list("children")


class HtmlDocument(HtmlBase):
    """Class to allow for quick creation of HTML data reports."""
    markup = Markup("html")
    markup.children = ["head", "body"]

    def __init__(self, html_filename=None, resource_folder=None, create_folders=True,
                 clear_resource_folder=False, clear_report_folder=False, attributes=None):
        super(HtmlDocument, self).__init__(attributes=attributes)
        self.head = Head()
        self.body = Body()
        self.styles = self.head.style.styles
        self.resource_folder = resource_folder
        self.filename = html_filename
        self.relative_path = None
        if self.filename and self.resource_folder:
            # Relative resource path
            self.relative_path = "." + self.resource_folder[len(os.path.split(self.filename)[0]):]
        self.clear()
        self.next_id_number = 0
        if html_filename is not None:
            report_folder = os.path.split(html_filename)[0]
        else:
            report_folder = None
        self.report_folder = report_folder
        # A counter for delivering unique ID's
        self.unique_id_count = 1
        # Resources are objects that have a 'save()' function that needs to be called when the report is saved
        self.resources = []
        # A dictionary of [name] = image
        self.figures_dict = {}
        # A dictionary to track unique IDs to prevent collisions. Keys are classes, values are lists of IDs.
        # To get an ID, call new_element_id(class).
        self.element_ids = {}
        if clear_report_folder and report_folder is not None and os.path.exists(report_folder):
            contents = glob.glob(os.path.join(report_folder, "*"))
            for item in contents:
                if os.path.isdir(item):
                    shutil.rmtree(item)
                else:
                    os.remove(item)
        if create_folders:
            if resource_folder is not None and not os.path.exists(resource_folder):
                os.makedirs(resource_folder)
            if report_folder is not None and not os.path.exists(report_folder):
                os.makedirs(report_folder)
        if clear_resource_folder and resource_folder is not None and os.path.exists(resource_folder):
            contents = glob.glob(os.path.join(resource_folder, "*"))
            for item in contents:
                os.remove(item)

        # Add all HTML elements as convenience functions to this class
        for name, obj in inspect.getmembers(sys.modules[__name__]):
            if inspect.isclass(obj) and issubclass(obj, HtmlBase) and name not in ["Placeholder",
                    "Paragraph", "Body", "Head", "TableRow", "BranchingTree"]:
                function_name = class_name_to_function_name(name)
                # Use a closure to create a function that calls obj and passes *args, **kwargs
                function = (lambda doc, html_class: lambda *args, **kwargs: doc.add(html_class(*args, **kwargs)))(self, obj)
                setattr(self, function_name, function)

    def new_unique_id(self):
        """Generate a new, unique html id."""
        new_id = "id" + str(self.unique_id_count)
        self.unique_id_count += 1
        return new_id

    # Deprecated. Use new_unique_id
    def new_element_id(self, element_class):
        """Create a new ID for the given element class and add it to self.element_ids to prevent name
        collisions."""
        ids = self.element_ids.get(element_class, [])
        self.element_ids[element_class] = ids
        prefix = element_class.__name__
        element_id = prefix + str(len(ids))
        ids.append(element_id)
        return element_id

    @classmethod
    def font_list(cls):
        """Return a list of common available fonts."""
        return ["Georgia", "Palatino Linotype", "Times New Roman", "Book Antiqua", "Palatino", "Arial", "Helvetica",
                "serif", "sans-serif", "Arial Black", "Gadget", "Comic Sans MS", "cursive", "Impact", "Charcoal",
                "Lucida Sans Unicode", "Lucida Grande", "Tahoma", "Geneva", "Trebuchet MS", "Helvetica", "Verdana", "Geneva",
                "Courier New", "Courier", "monospace", "Lucida Console", "Monaco", "monospace"]

    @classmethod
    def default_styles(cls):
        """Return a list of default style dictionaries."""
        border = {"border-width": "1px", "border-collapse": "collapse", "border-style": "solid", "border-color": "#000000",
                  "padding": "10px 10px"}
        return [("html", {"font-size": "25px", "margin": "10px", "font-name": "Times New Roman"}),
                ("p", {"width": "70%", "margin-left": "auto", "margin-right": "auto"}),
                ("b", {"margin": "0"}),
                ("h1, h2, h3, h4, h5, h6", {"text-align": "center"}),
                ("td", {"margin": "5px"}),
                ("table.border", border), ("table.border th", border), ("table.border td", border),
                ("div.horizontal_line", {"width": "100%", "height": "4px", "background": "#000000", "overflow": "hidden"})]

    def clear(self):
        """Start a new report and apply the default styles."""
        self.body.children = []
        self.auto_resource_count = 0
        self.styles.clear()
        for key, value in self.default_styles():
            self.styles[key] = value

    def add(self, html_element):
        """Add the given element to the body of the document."""
        self.body.children.append(html_element)
        return self.body.children[-1]

    def add_css_text(self, text):
        """Append the raw css text to the end of the head style section."""
        self.head.style.extra_text += "\n" + text

    def to_html(self, level=0, indent_string="  "):
        """Return an HTML string of this element and all of its children."""
        string = ""
        string += (level * indent_string) + "<!DOCTYPE html>\n"
        string += super(HtmlDocument, self).to_html(level, indent_string)
        return string

    def labels(self, xlabel, ylabel, title, add_figure=False):
        """Label the x-axis, y-axis, and title for the current matplotlib figure."""
        pylab.xlabel(xlabel)
        pylab.ylabel(ylabel)
        pylab.title(title)
        if add_figure:
            self.add_figure()

    def labels3d(self, axes, xlabel, ylabel, zlabel, title=None):
        """Label x, y, and z and add a title."""
        axes.set_xlabel(xlabel)
        axes.set_ylabel(ylabel)
        axes.set_zlabel(zlabel)
        if title:
            pylab.title(title)

    def resource_paths(self, resource_name=None, extension="png"):
        """Return the absolute and relative paths to the resource. If resource_name is none, a name is
        automatically assigned."""
        if resource_name is None:
            resource_name = "res_" + str(self.auto_resource_count)
            self.auto_resource_count += 1
        resource_name += "." + extension
        if self.resource_folder is None:
            raise Exception("No resource folder was provided.")
        if self.relative_path is None:
            raise Exception("resource folder and html filename must be provided to calculate relative resource path.")
        relative_path = os.path.join(self.relative_path, resource_name)
        absolute_path = os.path.join(self.resource_folder, resource_name)
        return absolute_path, relative_path

    def update_figure(self, figure_name, **kwargs):
        """Add the current figure or update a previously added figure."""
        if self.figures_dict.get(figure_name) is None:
            self.figures_dict[figure_name] = self.add_figure(figure_name, **kwargs)
        else:
            self.get_image(figure_name, **kwargs)

    def add_figure(self, figure_name=None, save=False, dpi=None, extension="png", tight=False,
                   numpy_data=None):
        """Add a matplotlib figure or numpy array as an image.
        numpy_data should be one of: [height, width, channels=3], [ch=3, height, width], or [height, width]
        with values between 0 and 255 with type uint8."""
        absolute_path, relative_path = self.resource_paths(figure_name, extension)
        if numpy_data is not None:
            if len(numpy_data.shape) == 2:
                # Black and white image (height, width). Add color channel
                numpy_data = np.stack([numpy_data]*3, 2)
            if numpy_data.shape[0] == 3 and numpy_data.shape[2] > 3:
                numpy_data = numpy_data.transpose((2, 1, 0))
            if numpy_data.dtype != np.uint8:
                numpy_data = numpy_data.astype(np.uint8)
            channels, height, width = numpy_data.shape
            im = PILImage.fromarray(numpy_data)
            im.save(absolute_path)
        else:
            # matplotlib
            bbox = pylab.gca().get_window_extent().transformed(pylab.gcf().dpi_scale_trans.inverted())
            fig = pylab.gcf()
            width, height = bbox.width * fig.dpi, bbox.height * fig.dpi

            kwargs = {}
            if dpi:
                kwargs["dpi"] = dpi
            if tight:
                kwargs["bbox_inches"] = "tight"
            pylab.savefig(absolute_path, **kwargs)
            pylab.close(pylab.gcf())
        image = self.image(relative_path)
        image.width = width
        image.height = height
        if save:
            self.save()
        return image

    def get_image(self, figure_name=None, dpi=None, extension="png", tight=False, numpy_data=None):
        """Save the current matplotlib figure or numpy image data and return an image object without adding
        it to the report (useful for creating tables)."""
        self.add_figure(figure_name, dpi=dpi, extension=extension, tight=tight, numpy_data=numpy_data)
        return self.body.children.pop(-1)

    def gif(self, images, frame_duration=0.2, loop_count=0):
        """Add a gif animation from the images. loop_count is an integer number of times to play (0 =
        forever)."""
        absolute_path, relative_path = self.resource_paths(None, "gif")
        import imageio
        with imageio.get_writer(absolute_path, mode='I', duration=frame_duration, loop=loop_count) as writer:
            for image in images:
                filename = os.path.join(self.report_folder, image.filename[2:])
                writer.append_data(imageio.imread(filename))
        # absolute_path, relative_path = self.resource_paths(None, "gif")
        # command_string = 'gifsicle --delay {}{} {} > "{}"'.format(
        #     delay_ms, " --loopcount=forever" * repeat,
        #     " ".join(['"{}"'.format(image.filename) for image in images]), absolute_path)
        # subprocess.call(command_string)
        self.add(Image(relative_path))

    def show_fonts(self, filename=None):
        """Pull up a window showing the available fonts."""
        self.styles[".font_showcase"] = {"font-size": "200%", "margin": "0px"}
        for font in self.font_list():
            self.text(font, attributes={"class": "font_showcase"}, css={"font-family": font})
        if filename:
            self.save(filename)

    def text(self, text, *args, print_text=False, print_=False, **kwargs):
        """Add a paragraph of text."""
        if print_text or print_:
            print(text)
        return self.add(Paragraph(text, *args, **kwargs))

    @classmethod
    def format_paragraph_text(cls, text):
        """Format a python triple quoted string as an HTML paragraph, eliminating single newlines (\n) and
        converting \r into \n."""
        return textwrap.dedent(text).replace("\n\n", "\r\r").replace("\n", " ").replace("\r", "\n")

    def paragraph(self, text):
        """Add a paragraph of text, removing newlines and indents, so that the multiline triple quote can be
        used. Newlines can manually be added as '\r'."""
        return self.text(self.format_paragraph_text(text))

    def horizontal_line(self, height=4, color="#000000"):
        """Add a horizontal line to the report 'height' pixels tall."""
        css = {}
        if str(height) + "px" != self.styles["div.horizontal_line"]["height"]:
            css["height"] = str(height) + "px"
        if color != self.styles["div.horizontal_line"]["background"]:
            css["background"] = color
        return self.div(css=css, attributes={"class": "horizontal_line"})

    def breaks(self, count=1):
        """Add 'count' number of breaks (blank lines) to the report."""
        for break_idx in range(count):
            self.add(Break())
        return self.body.children[-1]

    def heading(self, name="", break_count=2, header_level=1, print_=False):
        """Denote a new section in the report by 'break_count' breaks, followed by a horizontal line and a header
        with title 'name'."""
        self.breaks(break_count)
        self.horizontal_line()
        self.h(name, header_level=header_level)
        if print_:
            print(name)

    def add_html(self, html_text):
        """Add HTML to the report directly."""
        item = type("Html", (), {})()
        item.markup = Markup("")
        item.markup.to_markup_string = lambda text=html_text: text
        return self.add(item)

    def set_id_style(self, style_dictionary, html_object=None):
        """Set the css by id for the given object (default is the last object added to the document)."""
        if html_object is None:
            html_object = self.body.children[-1]
        if "id" not in html_object.attributes.keys():
            html_object.attributes["id"] = "item_" + str(self.next_id_number)
            self.next_id_number += 1
        item_id = html_object.attributes["id"]
        self.styles["#" + item_id] = style_dictionary

    def save(self, report_filename=None, print_saved=True):
        """Create and save report."""
        if report_filename is None:
            if self.filename is None:
                raise Exception("No filename was provided.")
            report_filename = self.filename
        self.filename = report_filename
        # Get the HTML report string
        html_string = self.to_html()
        # Write to file
        file_out = open(report_filename, 'wt')
        file_out.write(html_string)
        file_out.close()
        # Save any other resources
        for resource in self.resources:
            resource.save()
        if print_saved:
            print("Saved Report")

    def placeholder(self, exception_if_not_replaced=True):
        """Inserts a placeholder into the report that should be replaced by something else before the report
        is saved."""
        return self.add(PlaceHolder(self, len(self.body.children), exception_if_not_replaced))

    def open(self):
        """Open a previously saved report."""
        open_html_file(self.filename)

    def branching_tree(self, root_nodes, print_node_callback,
                       get_branches_callback=lambda node: node.branches, **kwargs):
        """Add a branching tree made of divs with any number of children per node."""
        self.add(BranchingTree(root_nodes, print_node_callback, get_branches_callback, self, **kwargs))
        # self.add(Div(["Break"], css={"page-break-after": "always", "position": "relative"}))




class BranchingTree(Div):
    """A branching tree made of divs with any number of children per node."""
    def __init__(self, root_nodes, print_node_callback, get_branches_callback, document, css=None,
                 attributes=None):
        if attributes is None:
            attributes = {}
        attributes["class"] = "tree"
        super().__init__(css=css, attributes=attributes)
        tree_css = """.tree ul {
  padding-top: 20px;
  position: relative;
  transition: all 0.5s;
  -webkit-transition: all 0.5s;
  -moz-transition: all 0.5s;
}

.tree li {
  float: left;
  text-align: center;
  list-style-type: none;
  position: relative;
  padding: 20px 5px 0 5px;
  transition: all 0.5s;
  -webkit-transition: all 0.5s;
  -moz-transition: all 0.5s;
}

.tree li::before,
.tree li::after {
  content: '';
  position: absolute;
  top: 0;
  right: 50%;
  border-top: 1px solid #ccc;
  width: 50%;
  height: 20px;
}

.tree li::after {
  right: auto;
  left: 50%;
  border-left: 1px solid #ccc;
}

.tree li:only-child::after,
.tree li:only-child::before {
  display: none;
}

.tree li:only-child {
  padding-top: 0;
}

.tree li:first-child::before,
.tree li:last-child::after {
  border: 0 none;
}

.tree li:last-child::before {
  border-right: 1px solid #ccc;
  border-radius: 0 5px 0 0;
  -webkit-border-radius: 0 5px 0 0;
  -moz-border-radius: 0 5px 0 0;
}

.tree li:first-child::after {
  border-radius: 5px 0 0 0;
  -webkit-border-radius: 5px 0 0 0;
  -moz-border-radius: 5px 0 0 0;
}

.tree ul ul::before {
  content: '';
  position: absolute;
  top: 0;
  left: 50%;
  border-left: 1px solid #ccc;
  width: 0;
  height: 20px;
}

.tree li div {
  border: 1px solid #ccc;
  padding: 5px 10px;
  text-decoration: none;
  color: #666;
  font-family: arial, verdana, tahoma;
  display: inline-block;
  border-radius: 5px;
  -webkit-border-radius: 5px;
  -moz-border-radius: 5px;
  transition: all 0.5s;
  -webkit-transition: all 0.5s;
  -moz-transition: all 0.5s;
}

.tree li div:hover,
.tree li div:hover+ul li div {
  background: #c8e4f8;
  color: #000;
  border: 1px solid #94a0b4;
}

.tree li div:hover + ul li::after,
.tree li div:hover + ul li::before,
.tree li div:hover + ul::before,
.tree li div:hover + ul ul::before {
  border-color: #94a0b4;
}
"""
        css_attr = "added_tree_css_text"
        if not hasattr(document, css_attr):
            document.add_css_text(tree_css)
            setattr(document, css_attr, True)

        # Create tree
        def add_node(node, print_node_callback, get_branches_callback, parent_ul):
            li = parent_ul.add(Li())
            div = li.add(Div())
            div.add(print_node_callback(node))
            children = get_branches_callback(node)
            if not children:
                return
            parent_ul = li.add(Ul())
            for child_node in children:
                add_node(child_node, print_node_callback, get_branches_callback, parent_ul)

        parent_ul = self.add(Ul())
        for node in root_nodes:
            add_node(node, print_node_callback, get_branches_callback, parent_ul)


def html_color(color):
    """Interpret 'color' as an HTML color."""
    if isinstance(color, str):
        if color.lower() == "r":
            return "#ff0000"
        elif color.lower() == "g":
            return "#00ff00"
        elif color.lower() == "b":
            return "#0000ff"
        elif color.startswith("#"):
            return color
    elif len(color) == 3:
        return '#' + ''.join([hex(int(float_color*255))[2:].zfill(2) for float_color in color])
    else:
        raise Exception("Color not recognized: " + str(color))


class PlainText(HtmlBase):
    """A custom string (no auto-inserted HTML tags)."""

    def __init__(self, text=""):
        self.markup = self
        self.text = text

    def to_html(self, level=0, indent_string="  "):
        return self.text

    def to_markup_string(self, child, level, indent, quote_strings):
        return self.text


class EmptyContainer(PlainText):
    """A container for HTML objects with no tag by itself."""
    def __init__(self):
        self.markup = self
        self.children = []

    def add(self, html_object):
        self.children.append(html_object)
        return html_object

    @property
    def text(self):
        string = ""
        for child in self.children:
            string += child.to_html()
        return string


class PlaceHolder:
    """A placeholder for items in a report that should be replaced before the report is saved."""
    def __init__(self, report, entry_index, raise_exception):
        self.report = report
        self.raise_exception = raise_exception
        self.item_to_replace = self

    def replace(self, html_object):
        """Replace this placeholder with the html object in the report."""
        idx = self.report.body.children.index(self.item_to_replace)
        self.report.body.children.pop(idx)
        self.report.body.children.insert(idx, html_object)
        self.item_to_replace = html_object

    def string(self, level=0, indent=0):
        if self.raise_exception:
            raise Exception("Placeholder was not replaced in HTML report entries.")
        return ""


def open_html_file(filename):
    """Try to open the given HTML file with Chrome."""
    if sys.platform == 'win32':
        subprocess.Popen(r'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe "' + filename + '"',
                         creationflags=0x00000008, close_fds=True)
    elif sys.platform == 'linux2':
        os.system('/opt/google/chrome/google-chrome "' + filename + '"')


if __name__ == "__main__":
    class SimpleExamp(XmlBase):
        markup = Markup("Simple")
        markup.children = ["value"]
        markup.attributes = ["attr", "attr2"]

        def __init__(self):
            self.key = "a"
            self.value = 32.16465165
            self.attr = "val"
            self.attr2 = -1

    temp = SimpleExamp()
    print(temp.to_xml())

    class LessSimple(XmlBase):
        markup = Markup("less_simple")
        markup.children = ["child1", "child2"]
        markup.attributes = ["attr1", "attr2"]

        def __init__(self):
            self.child1 = SimpleExamp()
            self.child2 = SimpleExamp()
            self.attr1 = "val1"
            self.attr2 = -3.2

    temp = LessSimple()
    print(temp.to_xml())

    class Complex(XmlBase):
        markup = Markup(None)
        markup.use_tag_property("tag")
        markup.use_attributes_direct("attributes")
        markup.use_child_list("children", tag_around_children=True)

        def __init__(self):
            self.tag = "complicated1"
            self.attributes = collections.OrderedDict([("key", "usa"), ("company", "mdt")])
            self.child1 = SimpleExamp()
            self.child2 = SimpleExamp()
            self.attr1 = 2
            self.attr2 = 3
            self.children = [LessSimple(), LessSimple()]
    temp = Complex()
    temp.tag = "NewTag"
    temp.attributes["new_attr"] = 3.1415
    temp.children[-1].attr2 = 0
    xml_string = temp.to_xml()
    print(xml_string)

    copy = Complex()
    copy.from_xml(xml_string, tag_class_dictionary={temp.markup.tag_map.get(temp): Complex,
                                                    LessSimple.markup.tag:LessSimple, SimpleExamp.markup.tag: SimpleExamp})
    print("from_xml:")
    print(copy.to_xml())
    matches = copy.to_xml() == xml_string
    print("Matches = " + str(matches))
    if not matches:
        raise Exception("Did not match")

    class Step(XmlBase):
        markup = Markup("step")
        markup.children = ["item"]

        def __init__(self):
            self.item = 6

    class XmlExamp(XmlBase):
        markup = Markup("log")
        markup.use_child_list("steps", tag_around_children=True)

        def __init__(self):
            self.steps = [Step(), Step()]

    temp = XmlExamp()
    print(temp.to_xml())

    print("done")

    doc = HtmlDocument()
    doc.h("Report Logger Results", header_level=2)
    doc.section("New Section", header_level=3)
    doc.horizontal_line()
    doc.horizontal_line(height=2, color="#a3b208")
    doc.breaks(1)
    doc.styles["body, div, th, td"] = {"font-size": "12px", "font-family": "verdana, sans-serif"}
    doc.styles["a:link, a:visited, a:active"] = {"color": "#00c"}
    doc.styles["a:hover"] = {"color": "#33f"}

    table = [["my table", "values"], [3, "thirty"]]
    doc.table(table, row_headers=True, border=True, css={"table-align": "center"})
    doc.show_fonts()
    print(doc.to_html())
    doc.save(r"C:\Temp\report.html")

    # new_doc = HtmlDocument()
    # new_doc.body.from_html(doc.body.to_html())
    # matches = new_doc.body.to_html() == doc.body.to_html()
    # if not matches:
    #     raise Exception("HTML did not match")
    print("done")
